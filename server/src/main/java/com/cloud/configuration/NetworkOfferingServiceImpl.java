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

import static com.cloud.offering.NetworkOffering.RoutingMode.Dynamic;
import static com.cloud.offering.NetworkOffering.RoutingMode.Static;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.network.CloneNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkOfferingsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.cloudstack.query.QueryService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.NetworkOfferingJoinDao;
import com.cloud.api.query.vo.NetworkOfferingJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;

import com.cloud.network.Ipv6Service;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offering.NetworkOffering.Detail;
import com.cloud.offerings.NetworkOfferingDetailsVO;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.google.common.collect.Sets;

@Component
public class NetworkOfferingServiceImpl implements NetworkOfferingService {

    protected Logger logger = LogManager.getLogger(getClass());

    public static final String PERACCOUNT = "peraccount";
    public static final String PERZONE = "perzone";

    private static final Set<Provider> VPC_ONLY_PROVIDERS = Sets.newHashSet(Provider.VPCVirtualRouter, Provider.InternalLbVm);
    private static final List<String> SUPPORTED_ROUTING_MODE_STRS = Arrays.asList(Static.toString().toLowerCase(), Dynamic.toString().toLowerCase());

    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    NetworkOfferingJoinDao networkOfferingJoinDao;
    @Inject
    NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOffServiceMapDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    DataCenterDao _zoneDao;
    @Inject
    DomainDao _domainDao;
    @Inject
    NetworkDao _networkDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    EntityManager _entityMgr;
    @Inject
    AnnotationDao annotationDao;
    @Inject
    AccountManager _accountMgr;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    NetworkService _networkSvc;
    @Inject
    NetworkModel _networkModel;
    @Inject
    MessageBus messageBus;
    @Inject
    DomainHelper domainHelper;

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_CREATE, eventDescription = "creating network offering")
    public NetworkOffering createNetworkOffering(final NetworkOfferingBaseCmd cmd) {
        final String name = cmd.getNetworkOfferingName();
        final String displayText = cmd.getDisplayText();
        final NetUtils.InternetProtocol internetProtocol = NetUtils.InternetProtocol.fromValue(cmd.getInternetProtocol());
        final String tags = cmd.getTags();
        final String trafficTypeString = cmd.getTraffictype();
        final boolean specifyVlan = cmd.getSpecifyVlan();
        final boolean conserveMode = cmd.getConserveMode();
        final String availabilityStr = cmd.getAvailability();
        Integer networkRate = cmd.getNetworkRate();
        TrafficType trafficType = null;
        Availability availability = null;
        Network.GuestType guestType = null;
        final boolean specifyIpRanges = cmd.getSpecifyIpRanges();
        final boolean isPersistent = cmd.getIsPersistent();
        final Map<String, String> detailsStr = cmd.getDetails();
        final Boolean egressDefaultPolicy = cmd.getEgressDefaultPolicy();
        Boolean forVpc = cmd.getForVpc();
        boolean forNsx = cmd.isForNsx();
        boolean forNetris = cmd.isForNetris();
        Boolean forTungsten = cmd.getForTungsten();
        String networkModeStr = cmd.getNetworkMode();
        boolean nsxSupportInternalLbSvc = cmd.getNsxSupportsInternalLbService();
        Integer maxconn = null;
        boolean enableKeepAlive = false;
        String servicePackageuuid = cmd.getServicePackageId();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();
        final boolean enable = cmd.getEnable();
        boolean specifyAsNumber = cmd.getSpecifyAsNumber();
        String routingModeString = cmd.getRoutingMode();
        // check if valid domain
        if (CollectionUtils.isNotEmpty(domainIds)) {
            for (final Long domainId: domainIds) {
                if (_domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        // check if valid zone
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            for (Long zoneId : zoneIds) {
                if (_zoneDao.findById(zoneId) == null)
                    throw new InvalidParameterValueException("Please specify a valid zone id");
            }
        }

        // if network offering is for tungsten check if every item from serviceProviderList has Tungsten-Fabric provider
        // except ConfigDrive
        if(Boolean.TRUE.equals(forTungsten)){
            for(Map.Entry<String, List<String>> item : cmd.getServiceProviders().entrySet()) {
                if (item.getValue().size() != 1 || !(item.getValue().contains("Tungsten") || item.getValue().contains("ConfigDrive"))) {
                    throw new InvalidParameterValueException("Please specify Tungsten-Fabric provider for the " + item.getKey() + " service provider.");
                }
            }
        }

        if ((Boolean.TRUE.equals(forTungsten) ? 1 : 0) + (forNetris ? 1 : 0) + (forNsx ? 1 : 0) > 1) {
            throw new InvalidParameterValueException("Network Offering cannot be for multiple providers - Tungsten-Fabric, NSX and Netris");
        }

        NetworkOffering.NetworkMode networkMode = null;
        if (networkModeStr != null) {
            if (!EnumUtils.isValidEnum(NetworkOffering.NetworkMode.class, networkModeStr)) {
                throw new InvalidParameterValueException("Invalid mode passed. Valid values: " + Arrays.toString(NetworkOffering.NetworkMode.values()));
            }
            networkMode = NetworkOffering.NetworkMode.valueOf(networkModeStr);
        }

        // Verify traffic type
        for (final TrafficType tType : TrafficType.values()) {
            if (tType.name().equalsIgnoreCase(trafficTypeString)) {
                trafficType = tType;
                break;
            }
        }
        if (trafficType == null) {
            throw new InvalidParameterValueException("Invalid value for traffictype. Supported traffic types: Public, Management, Control, Guest, Vlan or Storage");
        }

        // Only GUEST traffic type is supported in Acton
        if (trafficType != TrafficType.Guest) {
            throw new InvalidParameterValueException("Only traffic type " + TrafficType.Guest + " is supported in the current release");
        }

        // Verify offering type
        for (final Network.GuestType offType : Network.GuestType.values()) {
            if (offType.name().equalsIgnoreCase(cmd.getGuestIpType())) {
                guestType = offType;
                break;
            }
        }

        if (guestType == null) {
            throw new InvalidParameterValueException("Invalid \"type\" parameter is given; can have Shared and Isolated values");
        }

        if (internetProtocol != null) {
            if (!GuestType.Isolated.equals(guestType)) {
                throw new InvalidParameterValueException(String.format("%s is supported only for %s guest type", ApiConstants.INTERNET_PROTOCOL, GuestType.Isolated));
            }

            if (!Ipv6Service.Ipv6OfferingCreationEnabled.value() && !NetUtils.InternetProtocol.IPv4.equals(internetProtocol)) {
                throw new InvalidParameterValueException(String.format("Configuration %s needs to be enabled for creating IPv6 supported network offering", Ipv6Service.Ipv6OfferingCreationEnabled.key()));
            }
        }

        // Verify availability
        for (final Availability avlb : Availability.values()) {
            if (avlb.name().equalsIgnoreCase(availabilityStr)) {
                availability = avlb;
            }
        }

        if (availability == null) {
            throw new InvalidParameterValueException("Invalid value for Availability. Supported types: " + Availability.Required + ", " + Availability.Optional);
        }

        if (networkRate != null && networkRate < 0) {
            networkRate = 0;
        }

        final Long serviceOfferingId = cmd.getServiceOfferingId();

        if (serviceOfferingId != null) {
            _networkSvc.validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(serviceOfferingId);
        }

        NetworkOffering.RoutingMode routingMode = verifyRoutingMode(routingModeString);

        // configure service provider map
        final Map<Network.Service, Set<Network.Provider>> serviceProviderMap = new HashMap<>();
        final Set<Network.Provider> defaultProviders = new HashSet<>();

        // populate the services first
        for (final String serviceName : cmd.getSupportedServices()) {
            // validate if the service is supported
            final Service service = Network.Service.getService(serviceName);
            if (service == null || service == Service.Gateway) {
                throw new InvalidParameterValueException("Invalid service " + serviceName);
            }

            if (forVpc == null) {
                if (service == Service.SecurityGroup || service == Service.Firewall) {
                    forVpc = false;
                } else if (service == Service.NetworkACL) {
                    forVpc = true;
                }
            }

            if (service == Service.SecurityGroup) {
                // allow security group service for Shared networks only
                if (guestType != GuestType.Shared) {
                    throw new InvalidParameterValueException("Security group service is supported for network offerings with guest ip type " + GuestType.Shared);
                }
                final Set<Network.Provider> sgProviders = new HashSet<>();
                sgProviders.add(Provider.SecurityGroupProvider);
                serviceProviderMap.put(Network.Service.SecurityGroup, sgProviders);
                continue;
            }

            serviceProviderMap.put(service, defaultProviders);
        }

        // add gateway provider (if sourceNat provider is enabled)
        final Set<Provider> sourceNatServiceProviders = serviceProviderMap.get(Service.SourceNat);
        if (sourceNatServiceProviders != null && !sourceNatServiceProviders.isEmpty()) {
            serviceProviderMap.put(Service.Gateway, sourceNatServiceProviders);
        }

        // populate providers
        final Map<Provider, Set<Service>> providerCombinationToVerify = new HashMap<>();
        final Map<String, List<String>> svcPrv = cmd.getServiceProviders();
        Provider firewallProvider = null;
        Provider dhcpProvider = null;
        Boolean IsVrUserdataProvider = false;
        if (svcPrv != null) {
            for (final String serviceStr : svcPrv.keySet()) {
                final Network.Service service = Network.Service.getService(serviceStr);
                if (serviceProviderMap.containsKey(service)) {
                    final Set<Provider> providers = new HashSet<>();
                    // Allow to specify more than 1 provider per service only if
                    // the service is LB
                    if (!serviceStr.equalsIgnoreCase(Service.Lb.getName()) && svcPrv.get(serviceStr) != null && svcPrv.get(serviceStr).size() > 1) {
                        throw new InvalidParameterValueException("In the current release only one provider can be " + "specified for the service if the service is not LB");
                    }
                    for (final String prvNameStr : svcPrv.get(serviceStr)) {
                        // check if provider is supported
                        final Network.Provider provider = Network.Provider.getProvider(prvNameStr);
                        if (provider == null) {
                            throw new InvalidParameterValueException("Invalid service provider: " + prvNameStr);
                        }

                        if (provider == Provider.PaloAlto) {
                            firewallProvider = Provider.PaloAlto;
                        }

                        if ((service == Service.PortForwarding || service == Service.StaticNat) && provider == Provider.VirtualRouter) {
                            firewallProvider = Provider.VirtualRouter;
                        }

                        if (forVpc == null && VPC_ONLY_PROVIDERS.contains(provider)) {
                            forVpc = true;
                        }

                        if (forTungsten == null && Provider.Tungsten.equals(provider)){
                            forTungsten = true;
                        }

                        if (service == Service.Dhcp) {
                            dhcpProvider = provider;
                        }

                        if (service == Service.UserData && provider == Provider.VirtualRouter) {
                            IsVrUserdataProvider = true;
                        }

                        providers.add(provider);

                        Set<Service> serviceSet = null;
                        if (providerCombinationToVerify.get(provider) == null) {
                            serviceSet = new HashSet<>();
                        } else {
                            serviceSet = providerCombinationToVerify.get(provider);
                        }
                        serviceSet.add(service);
                        providerCombinationToVerify.put(provider, serviceSet);

                    }
                    serviceProviderMap.put(service, providers);
                } else {
                    throw new InvalidParameterValueException("Service " + serviceStr + " is not enabled for the network " + "offering, can't add a provider to it");
                }
            }
        }

        // dhcp provider and userdata provider should be same because vm will be contacting dhcp server for user data.
        if (dhcpProvider == null && IsVrUserdataProvider) {
            logger.debug("User data provider VR can't be selected without VR as dhcp provider. In this case VM fails to contact the DHCP server for userdata");
            throw new InvalidParameterValueException("Without VR as dhcp provider, User data can't selected for VR. Please select VR as DHCP provider ");
        }

        // validate providers combination here
        _networkModel.canProviderSupportServices(providerCombinationToVerify);

        // validate the LB service capabilities specified in the network
        // offering
        final Map<Capability, String> lbServiceCapabilityMap = cmd.getServiceCapabilities(Service.Lb);
        if (!serviceProviderMap.containsKey(Service.Lb) && lbServiceCapabilityMap != null && !lbServiceCapabilityMap.isEmpty()) {
            throw new InvalidParameterValueException("Capabilities for LB service can be specifed only when LB service is enabled for network offering.");
        }
        validateLoadBalancerServiceCapabilities(lbServiceCapabilityMap);

        if (lbServiceCapabilityMap != null && !lbServiceCapabilityMap.isEmpty()) {
            maxconn = cmd.getMaxconnections();
            if (maxconn == null) {
                maxconn = NetworkOrchestrationService.NETWORK_LB_HAPROXY_MAX_CONN.value();
            }
        }
        if (cmd.getKeepAliveEnabled() != null && cmd.getKeepAliveEnabled()) {
            enableKeepAlive = true;
        }

        // validate the Source NAT service capabilities specified in the network
        // offering
        final Map<Capability, String> sourceNatServiceCapabilityMap = cmd.getServiceCapabilities(Service.SourceNat);
        if (!serviceProviderMap.containsKey(Service.SourceNat) && sourceNatServiceCapabilityMap != null && !sourceNatServiceCapabilityMap.isEmpty()) {
            throw new InvalidParameterValueException("Capabilities for source NAT service can be specified only when source NAT service is enabled for network offering.");
        }
        validateSourceNatServiceCapablities(sourceNatServiceCapabilityMap);

        // validate the Static Nat service capabilities specified in the network
        // offering
        final Map<Capability, String> staticNatServiceCapabilityMap = cmd.getServiceCapabilities(Service.StaticNat);
        if (!serviceProviderMap.containsKey(Service.StaticNat) && sourceNatServiceCapabilityMap != null && !staticNatServiceCapabilityMap.isEmpty()) {
            throw new InvalidParameterValueException("Capabilities for static NAT service can be specified only when static NAT service is enabled for network offering.");
        }
        validateStaticNatServiceCapablities(staticNatServiceCapabilityMap);

        // validate the 'Connectivity' service capabilities specified in the network offering, if 'Connectivity' service
        // is in the supported services of network offering
        final Map<Capability, String> connectivityServiceCapabilityMap = cmd.getServiceCapabilities(Service.Connectivity);
        if (!serviceProviderMap.containsKey(Service.Connectivity) &&
                connectivityServiceCapabilityMap != null && !connectivityServiceCapabilityMap.isEmpty())  {
            throw new InvalidParameterValueException("Capabilities for 'Connectivity' service can be specified " +
                    "only when Connectivity service is enabled for network offering.");
        }
        validateConnectivityServiceCapablities(guestType, serviceProviderMap.get(Service.Connectivity), connectivityServiceCapabilityMap);

        final Map<Service, Map<Capability, String>> serviceCapabilityMap = new HashMap<>();
        serviceCapabilityMap.put(Service.Lb, lbServiceCapabilityMap);
        serviceCapabilityMap.put(Service.SourceNat, sourceNatServiceCapabilityMap);
        serviceCapabilityMap.put(Service.StaticNat, staticNatServiceCapabilityMap);
        serviceCapabilityMap.put(Service.Connectivity, connectivityServiceCapabilityMap);

        final Map<Capability, String> gatewayServiceCapabilityMap = cmd.getServiceCapabilities(Service.Gateway);
        if (MapUtils.isNotEmpty(gatewayServiceCapabilityMap)) {
            serviceCapabilityMap.put(Service.Gateway, gatewayServiceCapabilityMap);
        }

        // if Firewall service is missing, add Firewall service/provider
        // combination
        if (firewallProvider != null) {
            logger.debug("Adding Firewall service with provider " + firewallProvider.getName());
            final Set<Provider> firewallProviderSet = new HashSet<>();
            firewallProviderSet.add(firewallProvider);
            serviceProviderMap.put(Service.Firewall, firewallProviderSet);
            if (!(firewallProvider.getName().equals(Provider.JuniperSRX.getName()) || firewallProvider.getName().equals(Provider.PaloAlto.getName()) || firewallProvider.getName()
                    .equals(Provider.VirtualRouter.getName())) && egressDefaultPolicy == false) {
                throw new InvalidParameterValueException("Firewall egress with default policy " + egressDefaultPolicy + " is not supported by the provider "
                        + firewallProvider.getName());
            }
        }

        final Map<NetworkOffering.Detail, String> details = new HashMap<>();
        if (detailsStr != null) {
            for (final String detailStr : detailsStr.keySet()) {
                NetworkOffering.Detail offDetail = null;
                for (final NetworkOffering.Detail supportedDetail : NetworkOffering.Detail.values()) {
                    if (detailStr.equalsIgnoreCase(supportedDetail.toString())) {
                        offDetail = supportedDetail;
                        break;
                    }
                }
                if (offDetail == null) {
                    throw new InvalidParameterValueException("Unsupported detail " + detailStr);
                }
                details.put(offDetail, detailsStr.get(detailStr));
            }
        }

        if (forVpc == null) {
            forVpc = false;
        }

        final NetworkOfferingVO offering = createNetworkOffering(name, displayText, trafficType, tags, specifyVlan, availability, networkRate, serviceProviderMap, false, guestType, false,
                serviceOfferingId, conserveMode, serviceCapabilityMap, specifyIpRanges, isPersistent, details, egressDefaultPolicy, maxconn, enableKeepAlive, forVpc, forTungsten, forNsx, forNetris, networkMode, domainIds, zoneIds, enable, internetProtocol, routingMode, specifyAsNumber);
        if (Boolean.TRUE.equals(forNsx) && nsxSupportInternalLbSvc) {
            offering.setInternalLb(true);
            offering.setPublicLb(false);
            _networkOfferingDao.update(offering.getId(), offering);
        }
        CallContext.current().setEventDetails(" ID: " + offering.getUuid() + " Name: " + name);
        CallContext.current().putContextParameter(NetworkOffering.class, offering.getId());
        return offering;
    }

    public static NetworkOffering.RoutingMode verifyRoutingMode(String routingModeString) {
        NetworkOffering.RoutingMode routingMode = null;
        if (routingModeString != null) {
            try {
                if (!SUPPORTED_ROUTING_MODE_STRS.contains(routingModeString.toLowerCase())) {
                    throw new IllegalArgumentException(String.format("Unsupported value: %s", routingModeString));
                }
                routingMode = routingModeString.equalsIgnoreCase(Static.toString()) ? Static : Dynamic;
            } catch (IllegalArgumentException e) {
                String msg = String.format("Invalid value %s for Routing Mode, Supported values: %s, %s.",
                        routingModeString, Static, Dynamic);
                throw new InvalidParameterValueException(msg);
            }
        }
        return routingMode;
    }

    void validateLoadBalancerServiceCapabilities(final Map<Capability, String> lbServiceCapabilityMap) {
        if (lbServiceCapabilityMap != null && !lbServiceCapabilityMap.isEmpty()) {
            if (lbServiceCapabilityMap.keySet().size() > 4 || !lbServiceCapabilityMap.containsKey(Capability.SupportedLBIsolation)) {
                throw new InvalidParameterValueException(String.format("Only %s capabilities can be specified for LB service",
                        StringUtils.join(Capability.SupportedLBIsolation.getName(), Capability.ElasticLb.getName(),
                                Capability.InlineMode.getName(), Capability.LbSchemes.getName(), Capability.VmAutoScaling.getName())));
            }

            for (final Capability cap : lbServiceCapabilityMap.keySet()) {
                final String value = lbServiceCapabilityMap.get(cap);
                if (cap == Capability.SupportedLBIsolation) {
                    final boolean dedicatedLb = value.contains("dedicated");
                    final boolean sharedLB = value.contains("shared");
                    if (dedicatedLb && sharedLB || !dedicatedLb && !sharedLB) {
                        throw new InvalidParameterValueException("Either dedicated or shared isolation can be specified for " + Capability.SupportedLBIsolation.getName());
                    }
                } else if (cap == Capability.ElasticLb) {
                    final boolean enabled = value.contains("true");
                    final boolean disabled = value.contains("false");
                    if (!enabled && !disabled) {
                        throw new InvalidParameterValueException("Unknown specified value for " + Capability.ElasticLb.getName());
                    }
                } else if (cap == Capability.InlineMode) {
                    final boolean enabled = value.contains("true");
                    final boolean disabled = value.contains("false");
                    if (!enabled && !disabled) {
                        throw new InvalidParameterValueException("Unknown specified value for " + Capability.InlineMode.getName());
                    }
                } else if (cap == Capability.LbSchemes) {
                    final boolean internalLb = value.contains("internal");
                    final boolean publicLb = value.contains("public");
                    if (!internalLb && !publicLb) {
                        throw new InvalidParameterValueException("Unknown specified value for " + Capability.LbSchemes.getName());
                    }
                } else if (cap == Capability.VmAutoScaling) {
                    final boolean enabled = value.contains("true");
                    final boolean disabled = value.contains("false");
                    if (!enabled && !disabled) {
                        throw new InvalidParameterValueException("Unknown specified value for " + Capability.VmAutoScaling.getName());
                    }
                } else {
                    throw new InvalidParameterValueException(String.format("Only %s capabilities can be specified for LB service",
                            StringUtils.join(Capability.SupportedLBIsolation.getName(), Capability.ElasticLb.getName(),
                                    Capability.InlineMode.getName(), Capability.LbSchemes.getName(), Capability.VmAutoScaling.getName())));
                }
            }
        }
    }

    void validateSourceNatServiceCapablities(final Map<Capability, String> sourceNatServiceCapabilityMap) {
        if (MapUtils.isNotEmpty(sourceNatServiceCapabilityMap) && (sourceNatServiceCapabilityMap.size() > 2 || ! sourceNatCapabilitiesContainValidValues(sourceNatServiceCapabilityMap))) {
            throw new InvalidParameterValueException("Only " + Capability.SupportedSourceNatTypes.getName()
                    + ", " + Capability.RedundantRouter
                    + " capabilities can be specified for source nat service");
        }
    }

    boolean sourceNatCapabilitiesContainValidValues(Map<Capability, String> sourceNatServiceCapabilityMap) {
        for (final Entry<Capability ,String> srcNatPair : sourceNatServiceCapabilityMap.entrySet()) {
            final Capability capability = srcNatPair.getKey();
            final String value = srcNatPair.getValue();
            if (Capability.SupportedSourceNatTypes.equals(capability)) {
                List<String> snatTypes = Arrays.asList(PERACCOUNT, PERZONE);
                if (! snatTypes.contains(value) || ( value.contains(PERACCOUNT) && value.contains(PERZONE))) {
                    throw new InvalidParameterValueException("Either peraccount or perzone source NAT type can be specified for "
                            + Capability.SupportedSourceNatTypes.getName());
                }
            } else if (Capability.RedundantRouter.equals(capability)) {
                if (! Arrays.asList("true", "false").contains(value.toLowerCase())) {
                    throw new InvalidParameterValueException("Unknown specified value for " + capability.getName());
                }
            } else {
                return false;
            }
        }
        return true;
    }

    void validateStaticNatServiceCapablities(final Map<Capability, String> staticNatServiceCapabilityMap) {
        if (staticNatServiceCapabilityMap != null && !staticNatServiceCapabilityMap.isEmpty()) {
            boolean eipEnabled = false;
            boolean associatePublicIP = true;
            for (final Capability capability : staticNatServiceCapabilityMap.keySet()) {
                final String value = staticNatServiceCapabilityMap.get(capability).toLowerCase();
                if (!(value.contains("true") ^ value.contains("false"))) {
                    throw new InvalidParameterValueException("Unknown specified value (" + value + ") for " + capability);
                }
                if (capability == Capability.ElasticIp) {
                    eipEnabled = value.contains("true");
                } else if (capability == Capability.AssociatePublicIP) {
                    associatePublicIP = value.contains("true");
                } else {
                    throw new InvalidParameterValueException("Only " + Capability.ElasticIp.getName() + " and " + Capability.AssociatePublicIP.getName()
                            + " capability can be sepcified for static nat service");
                }
            }
            if (!eipEnabled && associatePublicIP) {
                throw new InvalidParameterValueException("Capability " + Capability.AssociatePublicIP.getName() + " can only be set when capability "
                        + Capability.ElasticIp.getName() + " is true");
            }
        }
    }

    void validateConnectivityServiceCapablities(final Network.GuestType guestType, final Set<Provider> providers, final Map<Capability, String> connectivityServiceCapabilityMap) {
        if (connectivityServiceCapabilityMap != null && !connectivityServiceCapabilityMap.isEmpty()) {
            for (final Map.Entry<Capability, String>entry: connectivityServiceCapabilityMap.entrySet()) {
                final Capability capability = entry.getKey();
                if (capability == Capability.StretchedL2Subnet || capability == Capability.PublicAccess) {
                    final String value = entry.getValue().toLowerCase();
                    if (!(value.contains("true") ^ value.contains("false"))) {
                        throw new InvalidParameterValueException("Invalid value (" + value + ") for " + capability +
                                " should be true/false");
                    } else if (capability == Capability.PublicAccess && guestType != GuestType.Shared) {
                        throw new InvalidParameterValueException("Capability " + capability.getName() + " can only be enabled for network offerings " +
                                "with guest type Shared.");
                    }
                } else {
                    throw new InvalidParameterValueException("Capability " + capability.getName() + " can not be "
                            + " specified with connectivity service.");
                }
            }

            // validate connectivity service provider actually supports specified capabilities
            if (providers != null && !providers.isEmpty()) {
                for (Capability capability : connectivityServiceCapabilityMap.keySet()) {
                    _networkModel.providerSupportsCapability(providers, Service.Connectivity, capability);
                }
            }
        }
    }

    boolean isRedundantRouter(Set<Provider> providers, Service service, Map<Capability, String> sourceNatServiceCapabilityMap) {
        boolean redundantRouter = false;
        String param = sourceNatServiceCapabilityMap.get(Capability.RedundantRouter);
        if (param != null) {
            _networkModel.checkCapabilityForProvider(providers, service, Capability.RedundantRouter, param);
            redundantRouter = param.contains("true");
        }
        return redundantRouter;
    }

    boolean isSharedSourceNat(Map<Service, Set<Provider>> serviceProviderMap, Map<Capability, String> sourceNatServiceCapabilityMap) {
        boolean sharedSourceNat = false;
        String param = sourceNatServiceCapabilityMap.get(Capability.SupportedSourceNatTypes);
        if (param != null) {
            _networkModel.checkCapabilityForProvider(serviceProviderMap.get(Service.SourceNat), Service.SourceNat, Capability.SupportedSourceNatTypes, param);
            sharedSourceNat = param.contains(PERZONE);
        }
        return sharedSourceNat;
    }

    protected void validateNtwkOffDetails(final Map<Detail, String> details, final Map<Service, Set<Provider>> serviceProviderMap) {
        for (final Detail detail : details.keySet()) {

            Provider lbProvider = null;
            if (detail == NetworkOffering.Detail.InternalLbProvider || detail == NetworkOffering.Detail.PublicLbProvider) {
                // 1) Vaidate the detail values - have to match the lb provider
                // name
                final String providerStr = details.get(detail);
                if (Network.Provider.getProvider(providerStr) == null) {
                    throw new InvalidParameterValueException("Invalid value " + providerStr + " for the detail " + detail);
                }
                if (serviceProviderMap.get(Service.Lb) != null) {
                    for (final Provider provider : serviceProviderMap.get(Service.Lb)) {
                        if (provider.getName().equalsIgnoreCase(providerStr)) {
                            lbProvider = provider;
                            break;
                        }
                    }
                }

                if (lbProvider == null) {
                    throw new InvalidParameterValueException("Invalid value " + details.get(detail) + " for the detail " + detail
                            + ". The provider is not supported by the network offering");
                }

                // 2) validate if the provider supports the scheme
                final Set<Provider> lbProviders = new HashSet<>();
                lbProviders.add(lbProvider);
                if (detail == NetworkOffering.Detail.InternalLbProvider) {
                    _networkModel.checkCapabilityForProvider(lbProviders, Service.Lb, Capability.LbSchemes, Scheme.Internal.toString());
                } else if (detail == NetworkOffering.Detail.PublicLbProvider) {
                    _networkModel.checkCapabilityForProvider(lbProviders, Service.Lb, Capability.LbSchemes, Scheme.Public.toString());
                }
            }
        }
    }

    @Override
    @DB
    public NetworkOfferingVO createNetworkOffering(final String name, final String displayText, final TrafficType trafficType, String tags, final boolean specifyVlan,
                                                   final Availability availability,
                                                   final Integer networkRate, final Map<Service, Set<Provider>> serviceProviderMap, final boolean isDefault, final GuestType type, final boolean systemOnly,
                                                   final Long serviceOfferingId,
                                                   final boolean conserveMode, final Map<Service, Map<Capability, String>> serviceCapabilityMap, final boolean specifyIpRanges, final boolean isPersistent,
                                                   final Map<Detail, String> details, final boolean egressDefaultPolicy, final Integer maxconn, final boolean enableKeepAlive, Boolean forVpc,
                                                   Boolean forTungsten, boolean forNsx, boolean forNetris, NetworkOffering.NetworkMode networkMode, final List<Long> domainIds, final List<Long> zoneIds, final boolean enableOffering, final NetUtils.InternetProtocol internetProtocol,
                                                   final NetworkOffering.RoutingMode routingMode, final boolean specifyAsNumber) {

        String servicePackageUuid;
        String spDescription = null;
        if (details == null) {
            servicePackageUuid = null;
        } else {
            servicePackageUuid = details.get(NetworkOffering.Detail.servicepackageuuid);
            spDescription = details.get(NetworkOffering.Detail.servicepackagedescription);
        }


        final String multicastRateStr = _configDao.getValue("multicast.throttling.rate");
        final int multicastRate = multicastRateStr == null ? 10 : Integer.parseInt(multicastRateStr);
        tags = com.cloud.utils.StringUtils.cleanupTags(tags);

        // specifyIpRanges should always be true for Shared networks
        // specifyIpRanges can only be true for Isolated networks with no Source
        // Nat service
        if (specifyIpRanges) {
            if (type == GuestType.Isolated) {
                if (serviceProviderMap.containsKey(Service.SourceNat)) {
                    throw new InvalidParameterValueException("SpecifyIpRanges can only be true for Shared network offerings and Isolated with no SourceNat service");
                }
            }
        } else {
            if (type == GuestType.Shared) {
                throw new InvalidParameterValueException("SpecifyIpRanges should always be true for Shared network offerings");
            }
        }

        if (specifyAsNumber && !forNsx) {
            String msg = "SpecifyAsNumber can only be true for network offerings for NSX";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (specifyAsNumber && !Dynamic.equals(routingMode)) {
            String msg = "SpecifyAsNumber can only be true for Dynamic Route Mode network offerings";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (specifyAsNumber && Boolean.TRUE.equals(forVpc)) {
            String msg = "SpecifyAsNumber cannot be set for VPC network tiers. It needs to be defined at VPC level";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        // isPersistent should always be false for Shared network Offerings
        if (isPersistent && type == GuestType.Shared) {
            throw new InvalidParameterValueException("isPersistent should be false if network offering's type is " + type);
        }

        // Validate network mode
        if (networkMode != null) {
            if (type != GuestType.Isolated) {
                throw new InvalidParameterValueException("networkMode should be set only for Isolated network offerings");
            }
            if (NetworkOffering.NetworkMode.ROUTED.equals(networkMode)) {
                if (!RoutedIpv4Manager.RoutedNetworkVpcEnabled.value()) {
                    throw new InvalidParameterValueException(String.format("Configuration %s needs to be enabled for Routed networks", RoutedIpv4Manager.RoutedNetworkVpcEnabled.key()));
                }
                if (zoneIds != null) {
                    for (Long zoneId: zoneIds) {
                        if (!RoutedIpv4Manager.RoutedNetworkVpcEnabled.valueIn(zoneId)) {
                            throw new InvalidParameterValueException(String.format("Configuration %s needs to be enabled for Routed networks in zone (ID: %s)", RoutedIpv4Manager.RoutedNetworkVpcEnabled.key(), zoneId));
                        }
                    }
                }
                boolean useVirtualRouterOnly = true;
                for (Service service : serviceProviderMap.keySet()) {
                    Set<Provider> providers = serviceProviderMap.get(service);
                    if (Arrays.asList(Service.SourceNat, Service.StaticNat, Service.Lb, Service.PortForwarding, Service.Vpn).contains(service)) {
                        if (providers != null) {
                            throw new InvalidParameterValueException("SourceNat/StaticNat/Lb/PortForwarding/Vpn service are not supported in ROUTED mode");
                        }
                    }
                    if (useVirtualRouterOnly && Arrays.asList(Service.Firewall, Service.NetworkACL).contains(service)) {
                        for (Provider provider : providers) {
                            if (!Provider.VirtualRouter.equals(provider) && !Provider.VPCVirtualRouter.equals(provider)) {
                                useVirtualRouterOnly = false;
                                break;
                            }
                        }
                    }
                }
                if (useVirtualRouterOnly) {
                    // Add VirtualRouter/VPCVirtualRouter as provider of Gateway service
                    if (forVpc) {
                        serviceProviderMap.put(Service.Gateway, Sets.newHashSet(Provider.VPCVirtualRouter));
                    } else {
                        serviceProviderMap.put(Service.Gateway, Sets.newHashSet(Provider.VirtualRouter));
                    }
                } else {
                    Set<Provider> providers = serviceProviderMap.get(Service.NetworkACL);
                    serviceProviderMap.put(Service.Gateway, Sets.newHashSet(providers.iterator().next()));
                }
            }
        }

        // validate availability value
        if (availability == NetworkOffering.Availability.Required) {
            final boolean canOffBeRequired = type == GuestType.Isolated && serviceProviderMap.containsKey(Service.SourceNat);
            if (!canOffBeRequired) {
                throw new InvalidParameterValueException("Availability can be " + NetworkOffering.Availability.Required + " only for networkOfferings of type "
                        + GuestType.Isolated + " and with " + Service.SourceNat.getName() + " enabled");
            }

            // only one network offering in the system can be Required
            final List<NetworkOfferingVO> offerings = _networkOfferingDao.listByAvailability(Availability.Required, false);
            if (!offerings.isEmpty()) {
                throw new InvalidParameterValueException("System already has network offering id=" + offerings.get(0).getId() + " with availability " + Availability.Required);
            }
        }

        boolean dedicatedLb = false;
        boolean elasticLb = false;
        boolean sharedSourceNat = false;
        boolean redundantRouter = false;
        boolean elasticIp = false;
        boolean associatePublicIp = false;
        boolean inline = false;
        boolean publicLb = false;
        boolean internalLb = false;
        boolean strechedL2Subnet = false;
        boolean publicAccess = false;
        boolean vmAutoScaling = false;

        if (serviceCapabilityMap != null && !serviceCapabilityMap.isEmpty()) {
            final Map<Capability, String> lbServiceCapabilityMap = serviceCapabilityMap.get(Service.Lb);

            if (lbServiceCapabilityMap != null && !lbServiceCapabilityMap.isEmpty()) {
                final String isolationCapability = lbServiceCapabilityMap.get(Capability.SupportedLBIsolation);
                if (isolationCapability != null) {
                    _networkModel.checkCapabilityForProvider(serviceProviderMap.get(Service.Lb), Service.Lb, Capability.SupportedLBIsolation, isolationCapability);
                    dedicatedLb = isolationCapability.contains("dedicated");
                } else {
                    dedicatedLb = true;
                }

                final String param = lbServiceCapabilityMap.get(Capability.ElasticLb);
                if (param != null) {
                    elasticLb = param.contains("true");
                }

                final String inlineMode = lbServiceCapabilityMap.get(Capability.InlineMode);
                if (inlineMode != null) {
                    _networkModel.checkCapabilityForProvider(serviceProviderMap.get(Service.Lb), Service.Lb, Capability.InlineMode, inlineMode);
                    inline = inlineMode.contains("true");
                } else {
                    inline = false;
                }

                final String publicLbStr = lbServiceCapabilityMap.get(Capability.LbSchemes);
                if (serviceProviderMap.containsKey(Service.Lb)) {
                    if (publicLbStr != null) {
                        _networkModel.checkCapabilityForProvider(serviceProviderMap.get(Service.Lb), Service.Lb, Capability.LbSchemes, publicLbStr);
                        internalLb = publicLbStr.contains("internal");
                        publicLb = publicLbStr.contains("public");
                    }
                }

                final String vmAutoScalingStr = lbServiceCapabilityMap.get(Capability.VmAutoScaling);
                if (vmAutoScalingStr != null) {
                    _networkModel.checkCapabilityForProvider(serviceProviderMap.get(Service.Lb), Service.Lb, Capability.VmAutoScaling, vmAutoScalingStr);
                    vmAutoScaling = vmAutoScalingStr.contains("true");
                }
            }

            // in the current version of the code, publicLb and specificLb can't
            // both be set to true for the same network offering
            if (publicLb && internalLb) {
                throw new InvalidParameterValueException("Public lb and internal lb can't be enabled at the same time on the offering");
            }

            final Map<Capability, String> sourceNatServiceCapabilityMap = serviceCapabilityMap.get(Service.SourceNat);
            if (MapUtils.isNotEmpty(sourceNatServiceCapabilityMap)) {
                sharedSourceNat = isSharedSourceNat(serviceProviderMap, sourceNatServiceCapabilityMap);
                redundantRouter = isRedundantRouter(serviceProviderMap.get(Service.SourceNat), Service.SourceNat, sourceNatServiceCapabilityMap);
            }

            final Map<Capability, String> gatewayServiceCapabilityMap = serviceCapabilityMap.get(Service.Gateway);
            if (MapUtils.isNotEmpty(gatewayServiceCapabilityMap)) {
                redundantRouter = redundantRouter || isRedundantRouter(serviceProviderMap.get(Service.Gateway), Service.Gateway, gatewayServiceCapabilityMap);
            }

            final Map<Capability, String> staticNatServiceCapabilityMap = serviceCapabilityMap.get(Service.StaticNat);
            if (staticNatServiceCapabilityMap != null && !staticNatServiceCapabilityMap.isEmpty()) {
                final String param = staticNatServiceCapabilityMap.get(Capability.ElasticIp);
                if (param != null) {
                    elasticIp = param.contains("true");
                    final String associatePublicIP = staticNatServiceCapabilityMap.get(Capability.AssociatePublicIP);
                    if (associatePublicIP != null) {
                        associatePublicIp = associatePublicIP.contains("true");
                    }
                }
            }

            final Map<Capability, String> connectivityServiceCapabilityMap = serviceCapabilityMap.get(Service.Connectivity);
            if (connectivityServiceCapabilityMap != null && !connectivityServiceCapabilityMap.isEmpty()) {
                if (connectivityServiceCapabilityMap.containsKey(Capability.StretchedL2Subnet)) {
                    final String value = connectivityServiceCapabilityMap.get(Capability.StretchedL2Subnet);
                    if ("true".equalsIgnoreCase(value)) {
                        strechedL2Subnet = true;
                    }
                }

                if (connectivityServiceCapabilityMap.containsKey(Capability.PublicAccess)) {
                    final String value = connectivityServiceCapabilityMap.get(Capability.PublicAccess);
                    if ("true".equalsIgnoreCase(value)) {
                        publicAccess = true;
                    }
                }
            }
        }

        if (serviceProviderMap != null && serviceProviderMap.containsKey(Service.Lb) && !internalLb && !publicLb) {
            //if not specified, default public lb to true
            publicLb = true;
        }

        final NetworkOfferingVO offeringFinal = new NetworkOfferingVO(name, displayText, trafficType, systemOnly, specifyVlan, networkRate, multicastRate, isDefault, availability,
                tags, type, conserveMode, dedicatedLb, sharedSourceNat, redundantRouter, elasticIp, elasticLb, specifyIpRanges, inline, isPersistent, associatePublicIp, publicLb,
                internalLb, forVpc, egressDefaultPolicy, strechedL2Subnet, publicAccess);

        if (serviceOfferingId != null) {
            offeringFinal.setServiceOfferingId(serviceOfferingId);
        }
        offeringFinal.setNetworkMode(networkMode);

        if (enableOffering) {
            offeringFinal.setState(NetworkOffering.State.Enabled);
        }

        offeringFinal.setSpecifyAsNumber(specifyAsNumber);
        if (routingMode != null) {
            offeringFinal.setRoutingMode(routingMode);
        }

        // Set VM AutoScaling capability
        offeringFinal.setSupportsVmAutoScaling(vmAutoScaling);

        //Set Service package id
        offeringFinal.setServicePackage(servicePackageUuid);
        // validate the details
        if (details != null) {
            validateNtwkOffDetails(details, serviceProviderMap);
        }

        boolean vpcOff = false;
        boolean nsOff = false;

        if (serviceProviderMap != null && spDescription != null) {
            for (final Network.Service service : serviceProviderMap.keySet()) {
                final Set<Provider> providers = serviceProviderMap.get(service);
                if (providers != null && !providers.isEmpty()) {
                    for (final Network.Provider provider : providers) {
                        if (provider == Provider.VPCVirtualRouter) {
                            vpcOff = true;
                        }
                        if (provider == Provider.Netscaler) {
                            nsOff = true;
                        }
                    }
                }
            }
            if(vpcOff && nsOff) {
                if(!(spDescription.equalsIgnoreCase("A NetScalerVPX is dedicated per network.") || spDescription.contains("dedicated NetScaler"))) {
                    throw new InvalidParameterValueException("Only NetScaler Service Package with Dedicated Device Mode is Supported in VPC Type Guest Network");
                }
            }
        }

        return Transaction.execute(new TransactionCallback<NetworkOfferingVO>() {
            @Override
            public NetworkOfferingVO doInTransaction(final TransactionStatus status) {
                NetworkOfferingVO offering = offeringFinal;

                // 1) create network offering object
                logger.debug("Adding network offering " + offering);
                offering.setConcurrentConnections(maxconn);
                offering.setKeepAliveEnabled(enableKeepAlive);
                offering = _networkOfferingDao.persist(offering, details);
                // 2) populate services and providers
                if (serviceProviderMap != null) {
                    for (final Network.Service service : serviceProviderMap.keySet()) {
                        final Set<Provider> providers = serviceProviderMap.get(service);
                        if (providers != null && !providers.isEmpty()) {
                            boolean vpcOff = false;
                            for (final Network.Provider provider : providers) {
                                if (provider == Provider.VPCVirtualRouter) {
                                    vpcOff = true;
                                }
                                final NetworkOfferingServiceMapVO offService = new NetworkOfferingServiceMapVO(offering.getId(), service, provider);
                                _ntwkOffServiceMapDao.persist(offService);
                                logger.trace("Added service for the network offering: " + offService + " with provider " + provider.getName());
                            }

                            if (vpcOff && !forNsx && !forNetris) {
                                final List<Service> supportedSvcs = new ArrayList<>();
                                supportedSvcs.addAll(serviceProviderMap.keySet());
                                _vpcMgr.validateNtwkOffForVpc(offering, supportedSvcs);
                            }
                        } else {
                            final NetworkOfferingServiceMapVO offService = new NetworkOfferingServiceMapVO(offering.getId(), service, null);
                            _ntwkOffServiceMapDao.persist(offService);
                            logger.trace("Added service for the network offering: " + offService + " with null provider");
                        }
                    }
                    if (offering != null) {
                        // Filter child domains when both parent and child domains are present
                        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);
                        List<NetworkOfferingDetailsVO> detailsVO = new ArrayList<>();
                        for (Long domainId : filteredDomainIds) {
                            detailsVO.add(new NetworkOfferingDetailsVO(offering.getId(), Detail.domainid, String.valueOf(domainId), false));
                        }
                        if (CollectionUtils.isNotEmpty(zoneIds)) {
                            for (Long zoneId : zoneIds) {
                                detailsVO.add(new NetworkOfferingDetailsVO(offering.getId(), Detail.zoneid, String.valueOf(zoneId), false));
                            }
                        }
                        if (internetProtocol != null) {
                            detailsVO.add(new NetworkOfferingDetailsVO(offering.getId(), Detail.internetProtocol, String.valueOf(internetProtocol), true));
                        }
                        if (!detailsVO.isEmpty()) {
                            for (NetworkOfferingDetailsVO detail : detailsVO) {
                                networkOfferingDetailsDao.persist(detail);
                            }
                        }
                    }
                }

                return offering;
            }
        });
    }

    @Override
    public Pair<List<? extends NetworkOffering>, Integer> searchForNetworkOfferings(final ListNetworkOfferingsCmd cmd) {
        final Filter searchFilter = new Filter(NetworkOfferingJoinVO.class, "sortKey", QueryService.SortKeyAscending.value(), null, null);
        searchFilter.addOrderBy(NetworkOfferingJoinVO.class, "id", true);
        final Account caller = CallContext.current().getCallingAccount();
        final SearchCriteria<NetworkOfferingJoinVO> sc = networkOfferingJoinDao.createSearchCriteria();

        final Long id = cmd.getId();
        final Object name = cmd.getNetworkOfferingName();
        final Object displayText = cmd.getDisplayText();
        final Object trafficType = cmd.getTrafficType();
        final Object isDefault = cmd.getIsDefault();
        final Object specifyVlan = cmd.getSpecifyVlan();
        final Object availability = cmd.getAvailability();
        final Object state = cmd.getState();
        final Long domainId = cmd.getDomainId();
        final Long zoneId = cmd.getZoneId();
        DataCenter zone = null;
        final Long networkId = cmd.getNetworkId();
        final String guestIpType = cmd.getGuestIpType();
        final List<String> supportedServicesStr = cmd.getSupportedServices();
        final Object specifyIpRanges = cmd.getSpecifyIpRanges();
        final String tags = cmd.getTags();
        final Boolean isTagged = cmd.isTagged();
        final Boolean forVpc = cmd.getForVpc();
        final String routingMode = cmd.getRoutingMode();

        if (domainId != null) {
            Domain domain = _entityMgr.findById(Domain.class, domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find the domain by id=" + domainId);
            }
            if (!_domainDao.isChildDomain(caller.getDomainId(), domainId)) {
                throw new InvalidParameterValueException(String.format("Unable to list network offerings for domain: %s as caller does not have access for it", domain.getUuid()));
            }
        }

        if (zoneId != null) {
            zone = _entityMgr.findById(DataCenter.class, zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Unable to find the zone by id=" + zoneId);
            }
        }

        final Object keyword = cmd.getKeyword();

        if (keyword != null) {
            final SearchCriteria<NetworkOfferingJoinVO> ssc = networkOfferingJoinDao.createSearchCriteria();
            ssc.addOr("displayText", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        if (guestIpType != null) {
            sc.addAnd("guestType", SearchCriteria.Op.EQ, guestIpType);
        }

        if (displayText != null) {
            sc.addAnd("displayText", SearchCriteria.Op.LIKE, "%" + displayText + "%");
        }

        if (trafficType != null) {
            sc.addAnd("trafficType", SearchCriteria.Op.EQ, trafficType);
        }

        if (isDefault != null) {
            sc.addAnd("isDefault", SearchCriteria.Op.EQ, isDefault);
        }

        // only root admin can list network offering with specifyVlan = true
        if (specifyVlan != null) {
            sc.addAnd("specifyVlan", SearchCriteria.Op.EQ, specifyVlan);
        }

        if (availability != null) {
            sc.addAnd("availability", SearchCriteria.Op.EQ, availability);
        }

        if (state != null) {
            sc.addAnd("state", SearchCriteria.Op.EQ, state);
        }

        if (specifyIpRanges != null) {
            sc.addAnd("specifyIpRanges", SearchCriteria.Op.EQ, specifyIpRanges);
        }

        if (zone != null) {
            if (zone.getNetworkType() == NetworkType.Basic) {
                // return empty list as we don't allow to create networks in
                // basic zone, and shouldn't display networkOfferings
                return new Pair<>(new ArrayList<>(), 0);
            }
        }

        if (routingMode != null && EnumUtils.isValidEnumIgnoreCase(NetworkOffering.RoutingMode.class, routingMode)) {
            sc.addAnd("routingMode", SearchCriteria.Op.EQ, routingMode);
        }

        // Don't return system network offerings to the user
        sc.addAnd("systemOnly", SearchCriteria.Op.EQ, false);

        // if networkId is specified, list offerings available for upgrade only
        // (for this network)
        com.cloud.network.Network network = null;
        if (networkId != null) {
            // check if network exists and the caller can operate with it
            network = _networkModel.getNetwork(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Unable to find the network by id=" + networkId);
            }
            // Don't allow to update system network
            final NetworkOffering offering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
            if (offering.isSystemOnly()) {
                throw new InvalidParameterValueException("Can't update system networks");
            }

            _accountMgr.checkAccess(caller, null, true, network);

            final List<Long> offeringIds = _networkModel.listNetworkOfferingsForUpgrade(networkId);

            if (!offeringIds.isEmpty()) {
                sc.addAnd("id", SearchCriteria.Op.IN, offeringIds.toArray());
            } else {
                return new Pair<>(new ArrayList<>(), 0);
            }
        }

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (isTagged != null) {
            if (isTagged) {
                sc.addAnd("tags", SearchCriteria.Op.NNULL);
            } else {
                sc.addAnd("tags", SearchCriteria.Op.NULL);
            }
        }

        if (tags != null) {
            if (GuestType.Shared.name().equalsIgnoreCase(guestIpType)) {
                SearchCriteria<NetworkOfferingJoinVO> tagsSc = networkOfferingJoinDao.createSearchCriteria();
                tagsSc.addAnd("tags", SearchCriteria.Op.EQ, tags);
                tagsSc.addOr("isDefault", SearchCriteria.Op.EQ, true);
                sc.addAnd("tags", SearchCriteria.Op.SC, tagsSc);
            } else {
                sc.addAnd("tags", SearchCriteria.Op.EQ, tags);
            }
        }

        if (zoneId != null) {
            SearchBuilder<NetworkOfferingJoinVO> sb = networkOfferingJoinDao.createSearchBuilder();
            sb.and("zoneId", sb.entity().getZoneId(), SearchCriteria.Op.FIND_IN_SET);
            sb.or("zId", sb.entity().getZoneId(), SearchCriteria.Op.NULL);
            sb.done();
            SearchCriteria<NetworkOfferingJoinVO> zoneSC = sb.create();
            zoneSC.setParameters("zoneId", String.valueOf(zoneId));
            sc.addAnd("zoneId", SearchCriteria.Op.SC, zoneSC);
        }

        final List<NetworkOfferingJoinVO> offerings = networkOfferingJoinDao.search(sc, searchFilter);
        // Remove offerings that are not associated with caller's domain or domainId passed
        if ((!Account.Type.ADMIN.equals(caller.getType()) || domainId != null) && CollectionUtils.isNotEmpty(offerings)) {
            ListIterator<NetworkOfferingJoinVO> it = offerings.listIterator();
            while (it.hasNext()) {
                NetworkOfferingJoinVO offering = it.next();
                if (StringUtils.isEmpty(offering.getDomainId())) {
                    continue;
                }
                if (!_domainDao.domainIdListContainsAccessibleDomain(offering.getDomainId(), caller, domainId)) {
                    it.remove();
                }
            }
        }
        final Boolean sourceNatSupported = cmd.getSourceNatSupported();
        final List<String> pNtwkTags = new ArrayList<>();
        boolean checkForTags = false;
        boolean allowNullTag = false;
        if (zone != null) {
            allowNullTag = allowNetworkOfferingWithNullTag(zoneId, pNtwkTags);
            checkForTags = !pNtwkTags.isEmpty() || allowNullTag;
        }

        // filter by supported services
        final boolean listBySupportedServices = supportedServicesStr != null && !supportedServicesStr.isEmpty() && !offerings.isEmpty();
        final boolean checkIfProvidersAreEnabled = zoneId != null;
        final boolean parseOfferings = listBySupportedServices || sourceNatSupported != null || checkIfProvidersAreEnabled || forVpc != null || network != null;

        if (parseOfferings) {
            final List<NetworkOfferingJoinVO> supportedOfferings = new ArrayList<>();
            Service[] supportedServices = null;

            if (listBySupportedServices) {
                supportedServices = new Service[supportedServicesStr.size()];
                int i = 0;
                for (final String supportedServiceStr : supportedServicesStr) {
                    final Service service = Service.getService(supportedServiceStr);
                    if (service == null) {
                        throw new InvalidParameterValueException("Invalid service specified " + supportedServiceStr);
                    } else {
                        supportedServices[i] = service;
                    }
                    i++;
                }
            }

            for (final NetworkOfferingJoinVO offering : offerings) {
                boolean addOffering = true;
                List<Service> checkForProviders = new ArrayList<>();

                if (checkForTags && !checkNetworkOfferingTags(pNtwkTags, allowNullTag, offering.getTags())) {
                    continue;
                }

                if (listBySupportedServices) {
                    addOffering = addOffering && _networkModel.areServicesSupportedByNetworkOffering(offering.getId(), supportedServices);
                }

                if (checkIfProvidersAreEnabled) {
                    if (supportedServices != null && supportedServices.length > 0) {
                        checkForProviders = Arrays.asList(supportedServices);
                    } else {
                        checkForProviders = _networkModel.listNetworkOfferingServices(offering.getId());
                    }

                    addOffering = addOffering && _networkModel.areServicesEnabledInZone(zoneId, offering, checkForProviders);
                }

                if (sourceNatSupported != null) {
                    addOffering = addOffering && _networkModel.areServicesSupportedByNetworkOffering(offering.getId(), Network.Service.SourceNat) == sourceNatSupported;
                }

                if (forVpc != null) {
                    addOffering = addOffering && offering.isForVpc() == forVpc.booleanValue();
                } else if (network != null) {
                    addOffering = addOffering && offering.isForVpc() == (network.getVpcId() != null);
                }

                if (addOffering) {
                    supportedOfferings.add(offering);
                }

            }

            // Now apply pagination
            final List<NetworkOfferingJoinVO> wPagination = com.cloud.utils.StringUtils.applyPagination(supportedOfferings, cmd.getStartIndex(), cmd.getPageSizeVal());
            if (wPagination != null) {
                final Pair<List<? extends NetworkOffering>, Integer> listWPagination = new Pair<>(wPagination, supportedOfferings.size());
                return listWPagination;
            }
            return new Pair<>(supportedOfferings, supportedOfferings.size());
        } else {
            final List<NetworkOfferingJoinVO> wPagination = com.cloud.utils.StringUtils.applyPagination(offerings, cmd.getStartIndex(), cmd.getPageSizeVal());
            if (wPagination != null) {
                final Pair<List<? extends NetworkOffering>, Integer> listWPagination = new Pair<>(wPagination, offerings.size());
                return listWPagination;
            }
            return new Pair<>(offerings, offerings.size());
        }
    }

    private boolean allowNetworkOfferingWithNullTag(Long zoneId, List<String> allPhysicalNetworkTags) {
        boolean allowNullTag = false;
        final List<PhysicalNetworkVO> physicalNetworks = _physicalNetworkDao.listByZoneAndTrafficType(zoneId, TrafficType.Guest);
        for (final PhysicalNetworkVO physicalNetwork : physicalNetworks) {
            final List<String> physicalNetworkTags = physicalNetwork.getTags();
            if (CollectionUtils.isEmpty(physicalNetworkTags)) {
                if (!allowNullTag) {
                    allowNullTag = true;
                } else {
                    throw new CloudRuntimeException("There are more than 1 physical network with empty tag in the zone id=" + zoneId);
                }
            } else {
                allPhysicalNetworkTags.addAll(physicalNetworkTags);
            }
        }
        return allowNullTag;
    }

    private boolean checkNetworkOfferingTags(List<String> physicalNetworkTags, boolean allowNullTag, String offeringTags) {
      return (offeringTags != null || allowNullTag) && (offeringTags == null || physicalNetworkTags.contains(offeringTags));
    }

    @Override
    public boolean isOfferingForVpc(final NetworkOffering offering) {
        return offering.isForVpc();
    }

    @DB
    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_DELETE, eventDescription = "deleting network offering")
    public boolean deleteNetworkOffering(final DeleteNetworkOfferingCmd cmd) {
        final Long offeringId = cmd.getId();

        // Verify network offering id
        final NetworkOfferingVO offering = _networkOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new InvalidParameterValueException("unable to find network offering " + offeringId);
        } else if (offering.getRemoved() != null || offering.isSystemOnly()) {
            throw new InvalidParameterValueException("unable to find network offering " + offeringId);
        }

        CallContext.current().setEventDetails(" ID: " + offering.getUuid());

        // Don't allow to delete default network offerings
        if (offering.isDefault() == true) {
            throw new InvalidParameterValueException("Default network offering can't be deleted");
        }

        // don't allow to delete network offering if it's in use by existing
        // networks (the offering can be disabled
        // though)
        final int networkCount = _networkDao.getNetworkCountByNetworkOffId(offeringId);
        if (networkCount > 0) {
            throw new InvalidParameterValueException(String.format("Can't delete network offering %s as its used by %d networks. To make the network offering unavailable, disable it", offering, networkCount));
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.NETWORK_OFFERING.name(), offering.getUuid());

        networkOfferingDetailsDao.removeDetails(offeringId);

        if (_networkOfferingDao.remove(offeringId)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_CLONE, eventDescription = "cloning network offering")
    public NetworkOffering cloneNetworkOffering(final CloneNetworkOfferingCmd cmd) {
        final Long sourceOfferingId = cmd.getSourceOfferingId();

        final NetworkOfferingVO sourceOffering = _networkOfferingDao.findById(sourceOfferingId);
        if (sourceOffering == null) {
            throw new InvalidParameterValueException("Unable to find network offering with id " + sourceOfferingId);
        }

        String name = cmd.getNetworkOfferingName();
        if (name == null || name.isEmpty()) {
            throw new InvalidParameterValueException("Name is required when cloning a network offering");
        }

        NetworkOfferingVO existing = _networkOfferingDao.findByUniqueName(name);
        if (existing != null) {
            throw new InvalidParameterValueException("Network offering with name '" + name + "' already exists");
        }

        logger.info("Cloning network offering {} (id: {}) to new offering with name: {}",
                    sourceOffering.getName(), sourceOfferingId, name);

        Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap =
                _networkModel.getNetworkOfferingServiceProvidersMap(sourceOfferingId);

        validateProvider(sourceOffering, sourceServiceProviderMap, cmd.getProvider(), cmd.getNetworkMode());

        applySourceOfferingValuesToCloneCmd(cmd, sourceServiceProviderMap, sourceOffering);

        return createNetworkOffering(cmd);
    }

    private void validateProvider(NetworkOfferingVO sourceOffering,
                                  Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap,
                                  String detectedProvider, String networkMode) {

        detectedProvider = getExternalNetworkProvider(detectedProvider, sourceServiceProviderMap);
        // If this is an NSX/Netris offering, prevent network mode changes
        if (detectedProvider != null && (detectedProvider.equals("NSX") || detectedProvider.equals("Netris"))) {
            if (networkMode != null && sourceOffering.getNetworkMode() != null) {
                if (!networkMode.equalsIgnoreCase(sourceOffering.getNetworkMode().toString())) {
                    throw new InvalidParameterValueException(
                            String.format("Cannot change network mode when cloning %s provider network offerings. " +
                                            "Source offering has network mode '%s', but '%s' was specified. ",
                                    detectedProvider, sourceOffering.getNetworkMode(), networkMode));
                }
            }
        }
    }

    public static String getExternalNetworkProvider(String detectedProvider,
                                             Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap) {
        if (StringUtils.isNotEmpty(detectedProvider)) {
            return detectedProvider;
        }

        if (sourceServiceProviderMap == null || sourceServiceProviderMap.isEmpty()) {
            return null;
        }

        for (Set<Provider> providers : sourceServiceProviderMap.values()) {
            if (CollectionUtils.isEmpty(providers)) {
                continue;
            }
            for (Provider provider : providers) {
                if (provider == Provider.Nsx) {
                    return "NSX";
                }
                if (provider == Provider.Netris) {
                    return "Netris";
                }
            }
        }

        return null;
    }

    /**
     * Converts service provider map from internal format to API parameter format.
     */
    private Map<String, Map<String, String>> convertToApiParameterFormat(Map<String, List<String>> serviceProviderMap) {
        Map<String, Map<String, String>> apiFormatMap = new HashMap<>();
        int index = 0;

        for (Map.Entry<String, List<String>> entry : serviceProviderMap.entrySet()) {
            String serviceName = entry.getKey();
            List<String> providers = entry.getValue();

            for (String provider : providers) {
                Map<String, String> serviceProviderEntry = new HashMap<>();
                serviceProviderEntry.put("service", serviceName);
                serviceProviderEntry.put("provider", provider);
                apiFormatMap.put(String.valueOf(index), serviceProviderEntry);
                index++;
            }
        }

        return apiFormatMap;
    }

    private void applySourceOfferingValuesToCloneCmd(CloneNetworkOfferingCmd cmd,
                                                     Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap,
                                                     NetworkOfferingVO sourceOffering) {
        Long sourceOfferingId = sourceOffering.getId();

        // Build final services list with add/drop support
        List<String> finalServices = resolveFinalServicesList(cmd, sourceServiceProviderMap);

        Map<String, List<String>> finalServiceProviderMap = resolveServiceProviderMap(cmd, sourceServiceProviderMap, finalServices);

        Map<String, Map<String, String>> sourceServiceCapabilityList = reconstructNetworkServiceCapabilityList(sourceOffering);

        Map<String, String> sourceDetailsMap = getSourceOfferingDetails(sourceOfferingId);

        List<Long> sourceDomainIds = networkOfferingDetailsDao.findDomainIds(sourceOfferingId);
        List<Long> sourceZoneIds = networkOfferingDetailsDao.findZoneIds(sourceOfferingId);

        applyResolvedValuesToCommand(cmd, sourceOffering, finalServices, finalServiceProviderMap,
            sourceServiceCapabilityList, sourceDetailsMap, sourceDomainIds, sourceZoneIds);
    }

    private Map<String, String> getSourceOfferingDetails(Long sourceOfferingId) {
        List<NetworkOfferingDetailsVO> sourceDetailsVOs = networkOfferingDetailsDao.listDetails(sourceOfferingId);
        Map<String, String> sourceDetailsMap = new HashMap<>();
        for (NetworkOfferingDetailsVO detailVO : sourceDetailsVOs) {
            sourceDetailsMap.put(detailVO.getName(), detailVO.getValue());
        }
        return sourceDetailsMap;
    }

    private List<String> resolveFinalServicesList(CloneNetworkOfferingCmd cmd,
            Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap) {

        List<String> cmdServices = cmd.getSupportedServices();
        List<String> addServices = cmd.getAddServices();
        List<String> dropServices = cmd.getDropServices();

        if (cmdServices != null && !cmdServices.isEmpty()) {
            return cmdServices;
        }

        List<String> finalServices = new ArrayList<>();
        for (Network.Service service : sourceServiceProviderMap.keySet()) {
            if (service != Network.Service.Gateway) {
                finalServices.add(service.getName());
            }
        }

        if (dropServices != null && !dropServices.isEmpty()) {
            List<String> normalizedDropServices = new ArrayList<>();
            for (String serviceName : dropServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid service name in dropServices: " + serviceName);
                }
                normalizedDropServices.add(service.getName());
            }
            finalServices.removeAll(normalizedDropServices);
            logger.debug("Dropped services from clone: {}", normalizedDropServices);
        }

        if (addServices != null && !addServices.isEmpty()) {
            List<String> normalizedAddServices = new ArrayList<>();
            for (String serviceName : addServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid service name in addServices: " + serviceName);
                }
                String canonicalName = service.getName();
                if (!finalServices.contains(canonicalName)) {
                    finalServices.add(canonicalName);
                    normalizedAddServices.add(canonicalName);
                }
            }
            logger.debug("Added services to clone: {}", normalizedAddServices);
        }

        return finalServices;
    }

    private Map<String, List<String>> resolveServiceProviderMap(CloneNetworkOfferingCmd cmd,
            Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap, List<String> finalServices) {

        if (cmd.getServiceProviders() != null && !cmd.getServiceProviders().isEmpty()) {
            return cmd.getServiceProviders();
        }

        Map<String, List<String>> finalMap = new HashMap<>();
        for (Map.Entry<Network.Service, Set<Network.Provider>> entry : sourceServiceProviderMap.entrySet()) {
            String serviceName = entry.getKey().getName();
            if (finalServices.contains(serviceName)) {
                List<String> providers = new ArrayList<>();
                for (Network.Provider provider : entry.getValue()) {
                    providers.add(provider.getName());
                }
                finalMap.put(serviceName, providers);
            }
        }

        return finalMap;
    }

    private void applyResolvedValuesToCommand(CloneNetworkOfferingCmd cmd, NetworkOfferingVO sourceOffering,
            List<String> finalServices, Map<String, List<String>> finalServiceProviderMap, Map<String, Map<String, String>> sourceServiceCapabilityList,
            Map<String, String> sourceDetailsMap, List<Long> sourceDomainIds, List<Long> sourceZoneIds) {

        try {
            Map<String, String> requestParams = cmd.getFullUrlParams();

            if (cmd.getSupportedServices() == null || cmd.getSupportedServices().isEmpty()) {
                setField(cmd, "supportedServices", finalServices);
            }
            if (cmd.getServiceProviders() == null || cmd.getServiceProviders().isEmpty()) {
                Map<String, Map<String, String>> apiFormatMap = convertToApiParameterFormat(finalServiceProviderMap);
                setField(cmd, "serviceProviderList", apiFormatMap);
            }

            boolean hasCapabilityParams = requestParams.keySet().stream()
                .anyMatch(key -> key.startsWith(ApiConstants.SERVICE_CAPABILITY_LIST));

            if (!hasCapabilityParams && sourceServiceCapabilityList != null && !sourceServiceCapabilityList.isEmpty()) {
                // Filter capabilities to only include those for services in the final service list
                Map<String, Map<String, String>> filteredCapabilities = new HashMap<>();
                for (Map.Entry<String, Map<String, String>> entry : sourceServiceCapabilityList.entrySet()) {
                    Map<String, String> capabilityMap = entry.getValue();
                    String serviceName = capabilityMap.get("service");
                    if (serviceName != null && finalServices.contains(serviceName)) {
                        filteredCapabilities.put(entry.getKey(), capabilityMap);
                    }
                }

                if (!filteredCapabilities.isEmpty()) {
                    setField(cmd, "serviceCapabilitiesList", filteredCapabilities);
                }
            }

            applyIfNotProvided(cmd, requestParams, "displayText", ApiConstants.DISPLAY_TEXT, cmd.getDisplayText(), sourceOffering.getDisplayText());
            applyIfNotProvided(cmd, requestParams, "traffictype", ApiConstants.TRAFFIC_TYPE, cmd.getTraffictype(), sourceOffering.getTrafficType().toString());
            applyIfNotProvided(cmd, requestParams, "tags", ApiConstants.TAGS, cmd.getTags(), sourceOffering.getTags());
            applyIfNotProvided(cmd, requestParams, "availability", ApiConstants.AVAILABILITY, cmd.getAvailability(), Availability.Optional.toString());
            applyIfNotProvided(cmd, requestParams, "networkRate", ApiConstants.NETWORKRATE, cmd.getNetworkRate(), sourceOffering.getRateMbps());
            applyIfNotProvided(cmd, requestParams, "serviceOfferingId", ApiConstants.SERVICE_OFFERING_ID, cmd.getServiceOfferingId(), sourceOffering.getServiceOfferingId());
            applyIfNotProvided(cmd, requestParams, "guestIptype", ApiConstants.GUEST_IP_TYPE, cmd.getGuestIpType(), sourceOffering.getGuestType().toString());
            applyIfNotProvided(cmd, requestParams, "maxConnections", ApiConstants.MAX_CONNECTIONS, cmd.getMaxconnections(), sourceOffering.getConcurrentConnections());

            applyBooleanIfNotProvided(cmd, requestParams, "specifyVlan", ApiConstants.SPECIFY_VLAN, sourceOffering.isSpecifyVlan());
            applyBooleanIfNotProvided(cmd, requestParams, "conserveMode", ApiConstants.CONSERVE_MODE, sourceOffering.isConserveMode());
            applyBooleanIfNotProvided(cmd, requestParams, "specifyIpRanges", ApiConstants.SPECIFY_IP_RANGES, sourceOffering.isSpecifyIpRanges());
            applyBooleanIfNotProvided(cmd, requestParams, "isPersistent", ApiConstants.IS_PERSISTENT, sourceOffering.isPersistent());
            applyBooleanIfNotProvided(cmd, requestParams, "forVpc", ApiConstants.FOR_VPC, sourceOffering.isForVpc());
            applyBooleanIfNotProvided(cmd, requestParams, "egressDefaultPolicy", ApiConstants.EGRESS_DEFAULT_POLICY, sourceOffering.isEgressDefaultPolicy());
            applyBooleanIfNotProvided(cmd, requestParams, "keepAliveEnabled", ApiConstants.KEEPALIVE_ENABLED, sourceOffering.isKeepAliveEnabled());
            applyBooleanIfNotProvided(cmd, requestParams, "enable", ApiConstants.ENABLE, sourceOffering.getState() == NetworkOffering.State.Enabled);
            applyBooleanIfNotProvided(cmd, requestParams, "specifyAsNumber", ApiConstants.SPECIFY_AS_NUMBER, sourceOffering.isSpecifyAsNumber());

            if (!requestParams.containsKey(ApiConstants.INTERNET_PROTOCOL)) {
                String internetProtocol = networkOfferingDetailsDao.getDetail(sourceOffering.getId(), Detail.internetProtocol);
                if (internetProtocol != null) {
                    setField(cmd, "internetProtocol", internetProtocol);
                }
            }

            if (!requestParams.containsKey(ApiConstants.NETWORK_MODE) && sourceOffering.getNetworkMode() != null) {
                setField(cmd, "networkMode", sourceOffering.getNetworkMode().toString());
            }

            if (!requestParams.containsKey(ApiConstants.ROUTING_MODE) && sourceOffering.getRoutingMode() != null) {
                setField(cmd, "routingMode", sourceOffering.getRoutingMode().toString());
            }

            if (cmd.getDetails() == null || cmd.getDetails().isEmpty()) {
                if (!sourceDetailsMap.isEmpty()) {
                    setField(cmd, "details", sourceDetailsMap);
                }
            }

            if (cmd.getDomainIds() == null || cmd.getDomainIds().isEmpty()) {
                if (sourceDomainIds != null && !sourceDomainIds.isEmpty()) {
                    setField(cmd, "domainIds", sourceDomainIds);
                }
            }
            if (cmd.getZoneIds() == null || cmd.getZoneIds().isEmpty()) {
                if (sourceZoneIds != null && !sourceZoneIds.isEmpty()) {
                    setField(cmd, "zoneIds", sourceZoneIds);
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to apply some source offering parameters during clone: {}", e.getMessage());
        }
    }

    /**
     * Reconstructs the service capability list from the source network offering's stored capability flags.
     */
    private Map<String, Map<String, String>> reconstructNetworkServiceCapabilityList(NetworkOfferingVO sourceOffering) {
        Map<String, Map<String, String>> capabilityList = new HashMap<>();
        int index = 0;

        if (sourceOffering.isDedicatedLB()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.Lb.getName());
            cap.put("capabilitytype", Network.Capability.SupportedLBIsolation.getName());
            cap.put("capabilityvalue", "dedicated");
            capabilityList.put(String.valueOf(index++), cap);
        }
        if (sourceOffering.isElasticLb()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.Lb.getName());
            cap.put("capabilitytype", Network.Capability.ElasticLb.getName());
            cap.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap);
        }
        if (sourceOffering.isInline()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.Lb.getName());
            cap.put("capabilitytype", Network.Capability.InlineMode.getName());
            cap.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap);
        }
        if (sourceOffering.isPublicLb() || sourceOffering.isInternalLb()) {
            List<String> schemes = new ArrayList<>();
            if (sourceOffering.isPublicLb()) schemes.add("public");
            if (sourceOffering.isInternalLb()) schemes.add("internal");
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.Lb.getName());
            cap.put("capabilitytype", Network.Capability.LbSchemes.getName());
            cap.put("capabilityvalue", String.join(",", schemes));
            capabilityList.put(String.valueOf(index++), cap);
        }
        if (sourceOffering.isSupportsVmAutoScaling()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.Lb.getName());
            cap.put("capabilitytype", Network.Capability.VmAutoScaling.getName());
            cap.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap);
        }

        if (sourceOffering.isSharedSourceNat()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.SourceNat.getName());
            cap.put("capabilitytype", Network.Capability.SupportedSourceNatTypes.getName());
            cap.put("capabilityvalue", "perzone");
            capabilityList.put(String.valueOf(index++), cap);
        }

        if (sourceOffering.isRedundantRouter()) {
            Map<String, String> cap1 = new HashMap<>();
            cap1.put("service", Network.Service.SourceNat.getName());
            cap1.put("capabilitytype", Network.Capability.RedundantRouter.getName());
            cap1.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap1);

            Map<String, String> cap2 = new HashMap<>();
            cap2.put("service", Network.Service.Gateway.getName());
            cap2.put("capabilitytype", Network.Capability.RedundantRouter.getName());
            cap2.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap2);
        }

        if (sourceOffering.isElasticIp()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.StaticNat.getName());
            cap.put("capabilitytype", Network.Capability.ElasticIp.getName());
            cap.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap);
        }

        if (sourceOffering.isElasticIp() && sourceOffering.isAssociatePublicIP()) {
            Map<String, String> cap = new HashMap<>();
            cap.put("service", Network.Service.StaticNat.getName());
            cap.put("capabilitytype", Network.Capability.AssociatePublicIP.getName());
            cap.put("capabilityvalue", "true");
            capabilityList.put(String.valueOf(index++), cap);
        }

        return capabilityList;
    }

    public static void applyIfNotProvided(Object cmd, Map<String, String> requestParams, String fieldName,
            String apiConstant, Object currentValue, Object sourceValue) throws Exception {
        if ((requestParams == null || !requestParams.containsKey(apiConstant)) && sourceValue != null) {
            setField(cmd, fieldName, sourceValue);
        }
    }

    public static void applyBooleanIfNotProvided(Object cmd, Map<String, String> requestParams,
            String fieldName, String apiConstant, Boolean sourceValue) throws Exception {
        if ((requestParams == null || !requestParams.containsKey(apiConstant)) && sourceValue != null) {
            setField(cmd, fieldName, sourceValue);
        }
    }

    public static void setField(Object obj, String fieldName, Object value) throws Exception {
        Field field = findField(obj.getClass(), fieldName);
        if (field == null) {
            throw new NoSuchFieldException("Field '" + fieldName + "' not found in class hierarchy of " + obj.getClass().getName());
        }
        field.setAccessible(true);
        field.set(obj, value);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> currentClass = clazz;
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentClass = currentClass.getSuperclass();
            }
        }
        return null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_OFFERING_EDIT, eventDescription = "updating network offering")
    public NetworkOffering updateNetworkOffering(final UpdateNetworkOfferingCmd cmd) {
        final String displayText = cmd.getDisplayText();
        final Long id = cmd.getId();
        final String name = cmd.getNetworkOfferingName();
        final String availabilityStr = cmd.getAvailability();
        final Integer sortKey = cmd.getSortKey();
        final Integer maxconn = cmd.getMaxconnections();
        Availability availability = null;
        final String state = cmd.getState();
        final String tags = cmd.getTags();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();

        // Verify input parameters
        final NetworkOfferingVO offeringToUpdate = _networkOfferingDao.findById(id);
        if (offeringToUpdate == null) {
            throw new InvalidParameterValueException("unable to find network offering " + id);
        }

        CallContext.current().setEventDetails(" ID: " + offeringToUpdate.getUuid());

        List<Long> existingDomainIds = networkOfferingDetailsDao.findDomainIds(id);
        Collections.sort(existingDomainIds);

        List<Long> existingZoneIds = networkOfferingDetailsDao.findZoneIds(id);
        Collections.sort(existingZoneIds);

        // Don't allow to update system network offering
        if (offeringToUpdate.isSystemOnly()) {
            throw new InvalidParameterValueException("Can't update system network offerings");
        }

        // check if valid domain
        if (CollectionUtils.isNotEmpty(domainIds)) {
            for (final Long domainId: domainIds) {
                if (_domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        // check if valid zone
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            for (Long zoneId : zoneIds) {
                if (_zoneDao.findById(zoneId) == null)
                    throw new InvalidParameterValueException("Please specify a valid zone id");
            }
        }

        // Filter child domains when both parent and child domains are present
        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);
        Collections.sort(filteredDomainIds);

        List<Long> filteredZoneIds = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            filteredZoneIds.addAll(zoneIds);
        }
        Collections.sort(filteredZoneIds);

        final NetworkOfferingVO offering = _networkOfferingDao.createForUpdate(id);

        boolean updateNeeded = name != null || displayText != null || sortKey != null ||
                state != null || tags != null || availabilityStr != null || maxconn != null;

        if(updateNeeded) {
            if (name != null) {
                offering.setName(name);
            }

            if (displayText != null) {
                offering.setDisplayText(displayText);
            }

            if (sortKey != null) {
                offering.setSortKey(sortKey);
            }

            if (state != null) {
                boolean validState = false;
                for (final NetworkOffering.State st : NetworkOffering.State.values()) {
                    if (st.name().equalsIgnoreCase(state)) {
                        validState = true;
                        offering.setState(st);
                    }
                }
                if (!validState) {
                    throw new InvalidParameterValueException("Incorrect state value: " + state);
                }
            }

            if (tags != null) {
                List<DataCenterVO> dataCenters = _zoneDao.listAll();
                TrafficType trafficType = offeringToUpdate.getTrafficType();
                String oldTags = offeringToUpdate.getTags();

                for (DataCenterVO dataCenter : dataCenters) {
                    long zoneId = dataCenter.getId();
                    long newPhysicalNetworkId = _networkModel.findPhysicalNetworkId(zoneId, tags, trafficType);
                    if (oldTags != null) {
                        long oldPhysicalNetworkId = _networkModel.findPhysicalNetworkId(zoneId, oldTags, trafficType);
                        if (newPhysicalNetworkId != oldPhysicalNetworkId) {
                            throw new InvalidParameterValueException(String.format("New tags: selects different physical network for zone %s", dataCenter));
                        }
                    }
                }

                if (StringUtils.isBlank(tags)) {
                    offering.setTags(null);
                } else {
                    offering.setTags(tags);
                }
            }

            // Verify availability
            if (availabilityStr != null) {
                for (final Availability avlb : Availability.values()) {
                    if (avlb.name().equalsIgnoreCase(availabilityStr)) {
                        availability = avlb;
                    }
                }
                if (availability == null) {
                    throw new InvalidParameterValueException("Invalid value for Availability. Supported types: " + Availability.Required + ", " + Availability.Optional);
                } else {
                    if (availability == NetworkOffering.Availability.Required) {
                        final boolean canOffBeRequired = offeringToUpdate.getGuestType() == GuestType.Isolated && _networkModel.areServicesSupportedByNetworkOffering(
                                offeringToUpdate.getId(), Service.SourceNat);
                        if (!canOffBeRequired) {
                            throw new InvalidParameterValueException("Availability can be " + NetworkOffering.Availability.Required + " only for networkOfferings of type "
                                    + GuestType.Isolated + " and with " + Service.SourceNat.getName() + " enabled");
                        }

                        // only one network offering in the system can be Required
                        final List<NetworkOfferingVO> offerings = _networkOfferingDao.listByAvailability(Availability.Required, false);
                        if (!offerings.isEmpty() && offerings.get(0).getId() != offeringToUpdate.getId()) {
                            throw new InvalidParameterValueException("System already has network offering id=" + offerings.get(0).getId() + " with availability "
                                    + Availability.Required);
                        }
                    }
                    offering.setAvailability(availability);
                }
            }
            if (_ntwkOffServiceMapDao.areServicesSupportedByNetworkOffering(offering.getId(), Service.Lb)) {
                if (maxconn != null) {
                    offering.setConcurrentConnections(maxconn);
                }
            }

            if (!_networkOfferingDao.update(id, offering)) {
                return null;
            }
        }

        List<NetworkOfferingDetailsVO> detailsVO = new ArrayList<>();
        if(!filteredDomainIds.equals(existingDomainIds) || !filteredZoneIds.equals(existingZoneIds)) {
            SearchBuilder<NetworkOfferingDetailsVO> sb = networkOfferingDetailsDao.createSearchBuilder();
            sb.and("offeringId", sb.entity().getResourceId(), SearchCriteria.Op.EQ);
            sb.and("detailName", sb.entity().getName(), SearchCriteria.Op.EQ);
            sb.done();
            SearchCriteria<NetworkOfferingDetailsVO> sc = sb.create();
            sc.setParameters("offeringId", String.valueOf(id));
            if(!filteredDomainIds.equals(existingDomainIds)) {
                sc.setParameters("detailName", ApiConstants.DOMAIN_ID);
                networkOfferingDetailsDao.remove(sc);
                for (Long domainId : filteredDomainIds) {
                    detailsVO.add(new NetworkOfferingDetailsVO(id, Detail.domainid, String.valueOf(domainId), false));
                }
            }
            if(!filteredZoneIds.equals(existingZoneIds)) {
                sc.setParameters("detailName", ApiConstants.ZONE_ID);
                networkOfferingDetailsDao.remove(sc);
                for (Long zoneId : filteredZoneIds) {
                    detailsVO.add(new NetworkOfferingDetailsVO(id, Detail.zoneid, String.valueOf(zoneId), false));
                }
            }
        }
        if (!detailsVO.isEmpty()) {
            for (NetworkOfferingDetailsVO detailVO : detailsVO) {
                networkOfferingDetailsDao.persist(detailVO);
            }
        }

        return _networkOfferingDao.findById(id);
    }

    @Override
    public List<Long> getNetworkOfferingDomains(Long networkOfferingId) {
        final NetworkOffering offeringHandle = _entityMgr.findById(NetworkOffering.class, networkOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find network offering " + networkOfferingId);
        }
        return networkOfferingDetailsDao.findDomainIds(networkOfferingId);
    }

    @Override
    public List<Long> getNetworkOfferingZones(Long networkOfferingId) {
        final NetworkOffering offeringHandle = _entityMgr.findById(NetworkOffering.class, networkOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find network offering " + networkOfferingId);
        }
        return networkOfferingDetailsDao.findZoneIds(networkOfferingId);
    }

    @Override
    public Integer getNetworkOfferingNetworkRate(final long networkOfferingId, final Long dataCenterId) {

        // validate network offering information
        final NetworkOffering no = _entityMgr.findById(NetworkOffering.class, networkOfferingId);
        if (no == null) {
            throw new InvalidParameterValueException("Unable to find network offering by id=" + networkOfferingId);
        }

        Integer networkRate;
        if (no.getRateMbps() != null) {
            networkRate = no.getRateMbps();
        } else {
            networkRate = NetworkOrchestrationService.NetworkThrottlingRate.valueIn(dataCenterId);
        }

        // networkRate is unsigned int in networkOfferings table, and can't be
        // set to -1
        // so 0 means unlimited; we convert it to -1, so we are consistent with
        // all our other resources where -1 means unlimited
        if (networkRate == 0) {
            networkRate = -1;
        }

        return networkRate;
    }

    @Override
    public List<? extends NetworkOffering> listNetworkOfferings(final TrafficType trafficType, final boolean systemOnly) {
        final Filter searchFilter = new Filter(NetworkOfferingVO.class, "created", false, null, null);
        final SearchCriteria<NetworkOfferingVO> sc = _networkOfferingDao.createSearchCriteria();
        if (trafficType != null) {
            sc.addAnd("trafficType", SearchCriteria.Op.EQ, trafficType);
        }
        sc.addAnd("systemOnly", SearchCriteria.Op.EQ, systemOnly);

        return _networkOfferingDao.search(sc, searchFilter);
    }
}
