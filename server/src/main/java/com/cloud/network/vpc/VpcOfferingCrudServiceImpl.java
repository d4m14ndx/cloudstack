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
package com.cloud.network.vpc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vpc.CloneVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Ipv6Service;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.vpc.VpcOffering.State;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.network.vpc.dao.VpcOfferingDetailsDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.google.common.collect.Sets;

import static com.cloud.offering.NetworkOffering.RoutingMode.Dynamic;

/**
 * CRUD operations for {@link VpcOffering} — extracted from {@link VpcManagerImpl}
 * as part of the Phase 4 Spring-component decomposition.
 *
 * @see VpcOfferingCrudService
 */
@Component
public class VpcOfferingCrudServiceImpl implements VpcOfferingCrudService {

    protected Logger logger = LogManager.getLogger(getClass());

    // Constants duplicated per Playbook "Shared helpers across slices"
    public static final String SERVICE = "service";
    public static final String CAPABILITYTYPE = "capabilitytype";
    public static final String CAPABILITYVALUE = "capabilityvalue";
    public static final String TRUE_VALUE = "true";
    public static final String FALSE_VALUE = "false";

    @Inject
    VpcOfferingDao _vpcOffDao;
    @Inject
    VpcOfferingDetailsDao vpcOfferingDetailsDao;
    @Inject
    VpcOfferingServiceMapDao _vpcOffSvcMapDao;
    @Inject
    DomainDao domainDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    VpcDao vpcDao;
    @Inject
    NetworkService _ntwkSvc;
    @Inject
    NetworkModel _ntwkModel;
    @Inject
    DomainHelper domainHelper;
    @Inject
    VpcOfferingQueryService vpcOfferingQueryService;

    private final List<Service> nonSupportedServices = Arrays.asList(Service.SecurityGroup, Service.Firewall);

    // -------------------------------------------------------------------------
    // Public API — 6 methods
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_CREATE, eventDescription = "creating vpc offering", create = true)
    public VpcOffering createVpcOffering(CreateVPCOfferingCmd cmd) {
        final String vpcOfferingName = cmd.getVpcOfferingName();
        final String displayText = cmd.getDisplayText();
        final List<String> supportedServices = cmd.getSupportedServices();
        final Map<String, List<String>> serviceProviderList = cmd.getServiceProviders();
        final Map serviceCapabilityList = cmd.getServiceCapabilityList();
        final NetUtils.InternetProtocol internetProtocol = NetUtils.InternetProtocol.fromValue(cmd.getInternetProtocol());
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();
        final String provider = cmd.getProvider();
        final Boolean forNsx = Objects.nonNull(provider) && provider.equalsIgnoreCase("NSX");
        final String networkModeStr = cmd.getNetworkMode();
        final boolean enable = Boolean.TRUE.equals(cmd.getEnable());

        NetworkOffering.NetworkMode networkMode = null;
        if (networkModeStr != null) {
            if (!EnumUtils.isValidEnum(NetworkOffering.NetworkMode.class, networkModeStr)) {
                throw new InvalidParameterValueException("Invalid mode passed. Valid values: " + Arrays.toString(NetworkOffering.NetworkMode.values()));
            }
            networkMode = NetworkOffering.NetworkMode.valueOf(networkModeStr);
        }
        if (NetworkOffering.NetworkMode.ROUTED.equals(networkMode)) {
            if (!RoutedIpv4Manager.RoutedNetworkVpcEnabled.value()) {
                throw new InvalidParameterValueException(String.format("Configuration %s needs to be enabled for Routed VPCs", RoutedIpv4Manager.RoutedNetworkVpcEnabled.key()));
            }
            if (zoneIds != null) {
                for (Long zoneId : zoneIds) {
                    if (!RoutedIpv4Manager.RoutedNetworkVpcEnabled.valueIn(zoneId)) {
                        throw new InvalidParameterValueException(String.format("Configuration %s needs to be enabled for Routed VPCs in zone (ID: %s)", RoutedIpv4Manager.RoutedNetworkVpcEnabled.key(), zoneId));
                    }
                }
            }
        }
        boolean specifyAsNumber = Boolean.TRUE.equals(cmd.getSpecifyAsNumber());
        String routingModeString = cmd.getRoutingMode();
        boolean conserveMode = cmd.isConserveMode();

        // check if valid domain
        if (CollectionUtils.isNotEmpty(cmd.getDomainIds())) {
            for (final Long domainId : cmd.getDomainIds()) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        // check if valid zone
        if (CollectionUtils.isNotEmpty(cmd.getZoneIds())) {
            for (Long zoneId : cmd.getZoneIds()) {
                if (_dcDao.findById(zoneId) == null)
                    throw new InvalidParameterValueException("Please specify a valid zone id");
            }
        }

        if (serviceOfferingId != null) {
            _ntwkSvc.validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(serviceOfferingId);
        }

        NetworkOffering.RoutingMode routingMode = ConfigurationManagerImpl.verifyRoutingMode(routingModeString);

        if (specifyAsNumber && !forNsx) {
            String msg = "SpecifyAsNumber can only be true for VPC offerings for NSX";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (specifyAsNumber && Dynamic != routingMode) {
            String msg = "SpecifyAsNumber can only be true for Dynamic Route Mode network offerings";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        return createVpcOffering(vpcOfferingName, displayText, supportedServices,
                serviceProviderList, serviceCapabilityList, internetProtocol, serviceOfferingId, provider, networkMode,
                domainIds, zoneIds, (enable ? State.Enabled : State.Disabled), routingMode, specifyAsNumber, conserveMode);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_CREATE, eventDescription = "creating vpc offering", create = true)
    public VpcOffering createVpcOffering(final String name, final String displayText, final List<String> supportedServices, final Map<String, List<String>> serviceProviders,
                                         final Map serviceCapabilityList, final NetUtils.InternetProtocol internetProtocol, final Long serviceOfferingId,
                                         final String externalProvider, final NetworkOffering.NetworkMode networkMode, List<Long> domainIds, List<Long> zoneIds, State state,
                                         NetworkOffering.RoutingMode routingMode, boolean specifyAsNumber, boolean conserveMode) {

        boolean isExternalProvider = externalProvider != null &&
                Arrays.asList("NSX", "Netris").stream().anyMatch(s -> s.equalsIgnoreCase(externalProvider));
        if (!isExternalProvider && CollectionUtils.isEmpty(supportedServices)) {
            throw new InvalidParameterValueException("Supported services needs to be provided");
        }

        if (!Ipv6Service.Ipv6OfferingCreationEnabled.value() && !(internetProtocol == null || NetUtils.InternetProtocol.IPv4.equals(internetProtocol))) {
            throw new InvalidParameterValueException(String.format("Configuration %s needs to be enabled for creating IPv6 supported VPC offering", Ipv6Service.Ipv6OfferingCreationEnabled.key()));
        }

        // Filter child domains when both parent and child domains are present
        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);

        final Map<Network.Service, Set<Network.Provider>> svcProviderMap = new HashMap<Network.Service, Set<Network.Provider>>();
        final Set<Network.Provider> defaultProviders = new HashSet<Network.Provider>();
        defaultProviders.add(Provider.VPCVirtualRouter);
        boolean sourceNatSvc = false;
        boolean firewallSvs = false;
        // populate the services first
        for (final String serviceName : supportedServices) {
            // validate if the service is supported
            final Service service = Network.Service.getService(serviceName);
            if (service == null || nonSupportedServices.contains(service)) {
                throw new InvalidParameterValueException("Service " + serviceName + " is not supported in VPC");
            }

            svcProviderMap.put(service, defaultProviders);
            if (service == Service.NetworkACL) {
                firewallSvs = true;
            }

            if (service == Service.SourceNat) {
                sourceNatSvc = true;
            }
        }

        if (!NetworkOffering.NetworkMode.ROUTED.equals(networkMode) && !sourceNatSvc) {
            logger.debug("Automatically adding source nat service to the list of VPC services");
            svcProviderMap.put(Service.SourceNat, defaultProviders);
        }

        if (!firewallSvs) {
            logger.debug("Automatically adding network ACL service to the list of VPC services");
            svcProviderMap.put(Service.NetworkACL, defaultProviders);
        }

        if (serviceProviders != null) {
            for (final Entry<String, List<String>> serviceEntry : serviceProviders.entrySet()) {
                final Network.Service service = Network.Service.getService(serviceEntry.getKey());
                if (svcProviderMap.containsKey(service)) {
                    final Set<Provider> providers = new HashSet<Provider>();
                    for (final String prvNameStr : serviceEntry.getValue()) {
                        // check if provider is supported
                        final Network.Provider provider = Network.Provider.getProvider(prvNameStr);
                        if (provider == null) {
                            throw new InvalidParameterValueException("Invalid service provider: " + prvNameStr);
                        }
                        if (NetworkOffering.NetworkMode.ROUTED.equals(networkMode)
                                && Arrays.asList(Service.SourceNat, Service.StaticNat, Service.Lb, Service.PortForwarding, Service.Vpn).contains(service)
                                && Provider.VPCVirtualRouter.equals(provider)) {
                            throw new InvalidParameterValueException("SourceNat/StaticNat/Lb/PortForwarding/Vpn service are not supported by VPC in ROUTED mode");
                        }

                        providers.add(provider);
                    }
                    svcProviderMap.put(service, providers);
                } else {
                    throw new InvalidParameterValueException("Service " + serviceEntry.getKey() + " is not enabled for the network " + "offering, can't add a provider to it");
                }
            }
        }

        // add gateway provider (if sourceNat provider is enabled)
        final Set<Provider> sourceNatServiceProviders = svcProviderMap.get(Service.SourceNat);
        Service redundantRouterService = Service.SourceNat;
        if (CollectionUtils.isNotEmpty(sourceNatServiceProviders)) {
            svcProviderMap.put(Service.Gateway, sourceNatServiceProviders);
        } else if (NetworkOffering.NetworkMode.ROUTED.equals(networkMode) && org.apache.commons.lang3.StringUtils.isBlank(externalProvider)) {
            // For Routed mode, add the Gateway service except for external providers such as NSX, Netris to not override the svcProviderMap mapping
            svcProviderMap.put(Service.Gateway, Sets.newHashSet(Provider.VPCVirtualRouter));
            redundantRouterService = Service.Gateway;
        }

        validateConnectivtyServiceCapabilities(svcProviderMap.get(Service.Connectivity), serviceCapabilityList);

        final boolean supportsDistributedRouter = isVpcOfferingSupportsDistributedRouter(serviceCapabilityList);
        final boolean offersRegionLevelVPC = isVpcOfferingForRegionLevelVpc(serviceCapabilityList);
        final boolean redundantRouter = isVpcOfferingRedundantRouter(serviceCapabilityList, redundantRouterService);
        final VpcOfferingVO offering = createVpcOfferingInternal(name, displayText, svcProviderMap, false, state, serviceOfferingId, supportsDistributedRouter, offersRegionLevelVPC,
                redundantRouter, networkMode, routingMode, specifyAsNumber, conserveMode);

        if (offering != null) {
            List<VpcOfferingDetailsVO> detailsVO = new ArrayList<>();
            for (Long domainId : filteredDomainIds) {
                detailsVO.add(new VpcOfferingDetailsVO(offering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
            if (CollectionUtils.isNotEmpty(zoneIds)) {
                for (Long zoneId : zoneIds) {
                    detailsVO.add(new VpcOfferingDetailsVO(offering.getId(), ApiConstants.ZONE_ID, String.valueOf(zoneId), false));
                }
            }
            if (internetProtocol != null) {
                detailsVO.add(new VpcOfferingDetailsVO(offering.getId(), ApiConstants.INTERNET_PROTOCOL, String.valueOf(internetProtocol), true));
            }
            if (!detailsVO.isEmpty()) {
                vpcOfferingDetailsDao.saveDetails(detailsVO);
            }
        }
        CallContext.current().setEventDetails(" ID: " + offering.getUuid() + " Name: " + name);
        CallContext.current().putContextParameter(VpcOffering.class, offering.getUuid());

        return offering;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_CLONE, eventDescription = "cloning VPC offering")
    public VpcOffering cloneVPCOffering(CloneVPCOfferingCmd cmd) {
        Long sourceVpcOfferingId = cmd.getSourceOfferingId();

        final VpcOffering sourceVpcOffering = _vpcOffDao.findById(sourceVpcOfferingId);
        if (sourceVpcOffering == null) {
            throw new InvalidParameterValueException("Unable to find source VPC offering by id " + sourceVpcOfferingId);
        }

        String name = cmd.getVpcOfferingName();
        if (name == null || name.isEmpty()) {
            throw new InvalidParameterValueException("Name is required when cloning a VPC offering");
        }

        VpcOfferingVO vpcOfferingVO = _vpcOffDao.findByUniqueName(name);
        if (vpcOfferingVO != null) {
            throw new InvalidParameterValueException(String.format("A VPC offering with name %s already exists", name));
        }

        logger.info("Cloning VPC offering {} (id: {}) to new offering with name: {}",
                sourceVpcOffering.getName(), sourceVpcOfferingId, name);

        Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap = vpcOfferingQueryService.getVpcOffSvcProvidersMap(sourceVpcOfferingId);
        validateProvider(sourceVpcOffering, sourceServiceProviderMap, cmd.getProvider(), cmd.getNetworkMode());

        applySourceOfferingValuesToCloneCmd(cmd, sourceServiceProviderMap, sourceVpcOffering);

        return createVpcOffering(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_DELETE, eventDescription = "deleting vpc offering")
    public boolean deleteVpcOffering(final long offId) {
        // Verify vpc offering id
        final VpcOfferingVO offering = _vpcOffDao.findById(offId);
        if (offering == null) {
            throw new InvalidParameterValueException("unable to find vpc offering " + offId);
        }
        CallContext.current().setEventDetails(" ID: " + offering.getUuid());

        // Don't allow to delete default vpc offerings
        if (offering.isDefault() == true) {
            throw new InvalidParameterValueException("Default network offering can't be deleted");
        }

        // don't allow to delete vpc offering if it's in use by existing vpcs
        // (the offering can be disabled though)
        final int vpcCount = vpcDao.getVpcCountByOfferingId(offId);
        if (vpcCount > 0) {
            throw new InvalidParameterValueException(String.format("Can't delete vpc offering %s as its used by %d vpcs. To make the network offering unavailable, disable it", offering, vpcCount));
        }

        if (_vpcOffDao.remove(offId)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_UPDATE, eventDescription = "updating vpc offering")
    public VpcOffering updateVpcOffering(long vpcOffId, String vpcOfferingName, String displayText, String state) {
        return updateVpcOfferingInternal(vpcOffId, vpcOfferingName, displayText, state, null, null, null);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VPC_OFFERING_UPDATE, eventDescription = "updating vpc offering")
    public VpcOffering updateVpcOffering(final UpdateVPCOfferingCmd cmd) {
        final Long offeringId = cmd.getId();
        final String vpcOfferingName = cmd.getVpcOfferingName();
        final String displayText = cmd.getDisplayText();
        final String state = cmd.getState();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();
        final Integer sortKey = cmd.getSortKey();

        // check if valid domain
        if (CollectionUtils.isNotEmpty(domainIds)) {
            for (final Long domainId : domainIds) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        // check if valid zone
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            for (Long zoneId : zoneIds) {
                if (_dcDao.findById(zoneId) == null)
                    throw new InvalidParameterValueException("Please specify a valid zone id");
            }
        }

        return updateVpcOfferingInternal(offeringId, vpcOfferingName, displayText, state, sortKey, domainIds, zoneIds);
    }

    // -------------------------------------------------------------------------
    // Internal helpers (private/protected — no spy use)
    // -------------------------------------------------------------------------

    @DB
    @Override
    public VpcOfferingVO createVpcOfferingInternal(final String name, final String displayText, final Map<Service, Set<Provider>> svcProviderMap,
                                              final boolean isDefault, final State state, final Long serviceOfferingId, final boolean supportsDistributedRouter, final boolean offersRegionLevelVPC,
                                              final boolean redundantRouter, NetworkOffering.NetworkMode networkMode, NetworkOffering.RoutingMode routingMode, boolean specifyAsNumber, boolean conserveMode) {

        return Transaction.execute(new TransactionCallback<VpcOfferingVO>() {
            @Override
            public VpcOfferingVO doInTransaction(final TransactionStatus status) {
                // create vpc offering object
                VpcOfferingVO offering = new VpcOfferingVO(name, displayText, isDefault, serviceOfferingId, supportsDistributedRouter, offersRegionLevelVPC, redundantRouter);

                if (state != null) {
                    offering.setState(state);
                }
                offering.setNetworkMode(networkMode);
                offering.setSpecifyAsNumber(specifyAsNumber);
                if (Objects.nonNull(routingMode)) {
                    offering.setRoutingMode(routingMode);
                }
                offering.setConserveMode(conserveMode);

                logger.debug("Adding vpc offering " + offering);
                offering = _vpcOffDao.persist(offering);
                // populate services and providers
                if (svcProviderMap != null) {
                    for (final Network.Service service : svcProviderMap.keySet()) {
                        final Set<Provider> providers = svcProviderMap.get(service);
                        if (providers != null && !providers.isEmpty()) {
                            for (final Network.Provider provider : providers) {
                                final VpcOfferingServiceMapVO offService = new VpcOfferingServiceMapVO(offering.getId(), service, provider);
                                _vpcOffSvcMapDao.persist(offService);
                                logger.trace("Added service for the vpc offering: " + offService + " with provider " + provider.getName());
                            }
                        } else {
                            throw new InvalidParameterValueException("Provider is missing for the VPC offering service " + service.getName());
                        }
                    }
                }

                return offering;
            }
        });
    }

    protected void checkCapabilityPerServiceProvider(final Set<Provider> providers, final Capability capability, final Service service) {
        // TODO Shouldn't it fail it there are no providers?
        if (providers != null) {
            for (final Provider provider : providers) {
                final NetworkElement element = _ntwkModel.getElementImplementingProvider(provider.getName());
                final Map<Service, Map<Capability, String>> capabilities = element.getCapabilities();
                if (capabilities != null && !capabilities.isEmpty()) {
                    final Map<Capability, String> connectivityCapabilities = capabilities.get(service);
                    if (connectivityCapabilities == null || connectivityCapabilities != null && !connectivityCapabilities.keySet().contains(capability)) {
                        throw new InvalidParameterValueException(String.format("Provider %s does not support %s  capability.", provider.getName(), capability.getName()));
                    }
                }
            }
        }
    }

    private void validateProvider(VpcOffering sourceVpcOffering,
                                  Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap,
                                  String provider, String networkMode) {
        provider = ConfigurationManagerImpl.getExternalNetworkProvider(provider, sourceServiceProviderMap);
        if (provider != null && (provider.equals("NSX") || provider.equals("Netris"))) {
            if (networkMode != null && sourceVpcOffering.getNetworkMode() != null) {
                if (!networkMode.equalsIgnoreCase(sourceVpcOffering.getNetworkMode().toString())) {
                    throw new InvalidParameterValueException(
                            String.format("Cannot change network mode when cloning %s provider VPC offerings. " +
                                            "Source offering has network mode '%s', but '%s' was specified. ",
                                    provider, sourceVpcOffering.getNetworkMode(), networkMode));
                }
            }
        }
    }

    private void applySourceOfferingValuesToCloneCmd(CloneVPCOfferingCmd cmd,
                                                     Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap,
                                                     VpcOffering sourceVpcOffering) {
        Long sourceOfferingId = sourceVpcOffering.getId();

        List<String> finalServices = resolveFinalServicesList(cmd, sourceServiceProviderMap);

        Map finalServiceProviderMap = resolveServiceProviderMap(cmd, sourceServiceProviderMap, finalServices);

        List<Long> sourceDomainIds = vpcOfferingDetailsDao.findDomainIds(sourceOfferingId);
        List<Long> sourceZoneIds = vpcOfferingDetailsDao.findZoneIds(sourceOfferingId);

        Map<String, Map<String, String>> sourceServiceCapabilityList = reconstructServiceCapabilityList(sourceVpcOffering);

        applyResolvedValuesToCommand(cmd, (VpcOfferingVO) sourceVpcOffering, finalServices, finalServiceProviderMap,
                sourceDomainIds, sourceZoneIds, sourceServiceCapabilityList);
    }

    /**
     * Reconstructs the service capability list from the source VPC offering's stored capability flags.
     * These capabilities were originally passed during creation and stored as boolean flags in the offering.
     *
     * Returns a Map in the format expected by CreateVPCOfferingCmd.serviceCapabilityList at runtime:
     * Map&lt;String, Map&lt;String, String&gt;&gt; with keys "0", "1", ... and values being maps with
     * "service", "capabilitytype", "capabilityvalue" entries (matches the parsed API parameter format).
     */
    private Map<String, Map<String, String>> reconstructServiceCapabilityList(VpcOffering sourceOffering) {
        Map<String, Map<String, String>> capabilityList = new HashMap<>();
        int index = 0;

        if (sourceOffering.isOffersRegionLevelVPC()) {
            Map<String, String> entry = new HashMap<>();
            entry.put(SERVICE, Network.Service.Connectivity.getName());
            entry.put(CAPABILITYTYPE, Network.Capability.RegionLevelVpc.getName());
            entry.put(CAPABILITYVALUE, "true");
            capabilityList.put(String.valueOf(index++), entry);
        }

        if (sourceOffering.isSupportsDistributedRouter()) {
            Map<String, String> entry = new HashMap<>();
            entry.put(SERVICE, Network.Service.Connectivity.getName());
            entry.put(CAPABILITYTYPE, Network.Capability.DistributedRouter.getName());
            entry.put(CAPABILITYVALUE, "true");
            capabilityList.put(String.valueOf(index++), entry);
        }

        if (sourceOffering.isRedundantRouter()) {
            Map<Network.Service, Set<Network.Provider>> serviceProviderMap = vpcOfferingQueryService.getVpcOffSvcProvidersMap(sourceOffering.getId());

            // Check which service has VPCVirtualRouter provider - SourceNat takes precedence
            Network.Service redundantRouterService = null;
            for (Network.Service service : Arrays.asList(Network.Service.SourceNat, Network.Service.Gateway, Network.Service.StaticNat)) {
                Set<Network.Provider> providers = serviceProviderMap.get(service);
                if (providers != null && providers.contains(Network.Provider.VPCVirtualRouter)) {
                    redundantRouterService = service;
                    break;
                }
            }

            if (redundantRouterService != null) {
                Map<String, String> entry = new HashMap<>();
                entry.put(SERVICE, redundantRouterService.getName());
                entry.put(CAPABILITYTYPE, Network.Capability.RedundantRouter.getName());
                entry.put(CAPABILITYVALUE, "true");
                capabilityList.put(String.valueOf(index), entry);
            }
        }

        return capabilityList;
    }

    private List<String> resolveFinalServicesList(CloneVPCOfferingCmd cmd,
                                                  Map<Network.Service, Set<Network.Provider>> sourceServiceProviderMap) {

        List<String> cmdServices = cmd.getSupportedServices();
        List<String> addServices = cmd.getAddServices();
        List<String> dropServices = cmd.getDropServices();

        if (cmdServices != null && !cmdServices.isEmpty()) {
            return cmdServices;
        }

        List<String> finalServices = new ArrayList<>();
        for (Network.Service service : sourceServiceProviderMap.keySet()) {
            finalServices.add(service.getName());
        }

        if (dropServices != null && !dropServices.isEmpty()) {
            List<String> normalizedDropServices = new ArrayList<>();
            for (String serviceName : dropServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null) {
                    throw new InvalidParameterValueException("Service " + serviceName + " is not supported in VPC");
                }
                normalizedDropServices.add(service.getName());
            }
            finalServices.removeAll(normalizedDropServices);
            logger.debug("Dropped services from clone: {}", dropServices);
        }

        if (addServices != null && !addServices.isEmpty()) {
            List<String> normalizedAddServices = new ArrayList<>();
            for (String serviceName : addServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null) {
                    throw new InvalidParameterValueException("Service " + serviceName + " is not supported in VPC");
                }
                String canonicalName = service.getName();
                if (!finalServices.contains(canonicalName)) {
                    finalServices.add(canonicalName);
                    normalizedAddServices.add(canonicalName);
                }
            }
            logger.debug("Added services to clone: {}", addServices);
        }

        return finalServices;
    }

    private Map<String, List<String>> resolveServiceProviderMap(CloneVPCOfferingCmd cmd,
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

    /**
     * Converts service provider map from Map<String, List<String>> to the indexed format
     * expected by CreateVPCOfferingCmd.serviceProviderList parameter.
     *
     * Input: {"Dhcp": ["VpcVirtualRouter"], "Dns": ["VpcVirtualRouter"]}
     * Output: {"0": {"service": "Dhcp", "provider": "VpcVirtualRouter"},
     *          "1": {"service": "Dns", "provider": "VpcVirtualRouter"}}
     */
    private Map<String, Map<String, String>> convertToServiceProviderListFormat(Map<String, List<String>> serviceProviderMap) {
        Map<String, Map<String, String>> result = new HashMap<>();
        int index = 0;

        for (Map.Entry<String, List<String>> entry : serviceProviderMap.entrySet()) {
            String serviceName = entry.getKey();
            List<String> providers = entry.getValue();

            for (String providerName : providers) {
                Map<String, String> serviceProviderEntry = new HashMap<>();
                serviceProviderEntry.put("service", serviceName);
                serviceProviderEntry.put("provider", providerName);
                result.put(String.valueOf(index++), serviceProviderEntry);
            }
        }

        return result;
    }

    private void applyResolvedValuesToCommand(CloneVPCOfferingCmd cmd, VpcOfferingVO sourceOffering,
                                              List<String> finalServices, Map finalServiceProviderMap,
                                              List<Long> sourceDomainIds, List<Long> sourceZoneIds,
                                              Map<String, Map<String, String>> sourceServiceCapabilityList) {
        try {
            if (cmd.getSupportedServices() == null || cmd.getSupportedServices().isEmpty()) {
                logger.debug("Setting supportedServices to {} services from source offering", finalServices.size());
                ConfigurationManagerImpl.setField(cmd, "supportedServices", finalServices);
            }

            if (cmd.getServiceProviders() == null || cmd.getServiceProviders().isEmpty()) {
                Map<String, Map<String, String>> convertedProviderMap = convertToServiceProviderListFormat(finalServiceProviderMap);
                logger.debug("Setting serviceProviderList with {} provider mappings", convertedProviderMap.size());
                ConfigurationManagerImpl.setField(cmd, "serviceProviderList", convertedProviderMap);
            }

            if ((cmd.getServiceCapabilityList() == null || cmd.getServiceCapabilityList().isEmpty())
                    && sourceServiceCapabilityList != null && !sourceServiceCapabilityList.isEmpty()) {
                Map<String, Map<String, String>> filteredCapabilities = filterServiceCapabilities(sourceServiceCapabilityList, finalServices);
                if (!filteredCapabilities.isEmpty()) {
                    ConfigurationManagerImpl.setField(cmd, "serviceCapabilityList", filteredCapabilities);
                }
            }

            if (cmd.getDisplayText() == null && sourceOffering.getDisplayText() != null) {
                ConfigurationManagerImpl.setField(cmd, "displayText", sourceOffering.getDisplayText());
            }

            if (cmd.getServiceOfferingId() == null && sourceOffering.getServiceOfferingId() != null) {
                ConfigurationManagerImpl.setField(cmd, "serviceOfferingId", sourceOffering.getServiceOfferingId());
            }

            Boolean enableFieldValue = getRawFieldValue(cmd, "enable", Boolean.class);
            if (enableFieldValue == null) {
                Boolean enableState = sourceOffering.getState() == VpcOffering.State.Enabled;
                ConfigurationManagerImpl.setField(cmd, "enable", enableState);
            }

            Boolean specifyAsNumberFieldValue = getRawFieldValue(cmd, "specifyAsNumber", Boolean.class);
            if (specifyAsNumberFieldValue == null) {
                ConfigurationManagerImpl.setField(cmd, "specifyAsNumber", sourceOffering.isSpecifyAsNumber());
            }

            if (cmd.getInternetProtocol() == null) {
                String internetProtocol = vpcOfferingDetailsDao.getDetail(sourceOffering.getId(), ApiConstants.INTERNET_PROTOCOL);
                if (internetProtocol != null) {
                    ConfigurationManagerImpl.setField(cmd, "internetProtocol", internetProtocol);
                }
            }

            if (cmd.getNetworkMode() == null && sourceOffering.getNetworkMode() != null) {
                ConfigurationManagerImpl.setField(cmd, "networkMode", sourceOffering.getNetworkMode().toString());
            }

            if (cmd.getRoutingMode() == null && sourceOffering.getRoutingMode() != null) {
                ConfigurationManagerImpl.setField(cmd, "routingMode", sourceOffering.getRoutingMode().toString());
            }

            if (cmd.getDomainIds() == null || cmd.getDomainIds().isEmpty()) {
                if (sourceDomainIds != null && !sourceDomainIds.isEmpty()) {
                    ConfigurationManagerImpl.setField(cmd, "domainIds", sourceDomainIds);
                }
            }

            if (cmd.getZoneIds() == null || cmd.getZoneIds().isEmpty()) {
                if (sourceZoneIds != null && !sourceZoneIds.isEmpty()) {
                    ConfigurationManagerImpl.setField(cmd, "zoneIds", sourceZoneIds);
                }
            }

        } catch (Exception e) {
            logger.error("Failed to apply source offering parameters during clone: {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to apply source offering parameters during VPC offering clone", e);
        }
    }

    private <T> T getRawFieldValue(Object obj, String fieldName, Class<T> expectedType) {
        try {
            java.lang.reflect.Field field = ConfigurationManagerImpl.findField(obj.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value == null || expectedType.isInstance(value)) {
                    return expectedType.cast(value);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not get raw field value for {}: {}", fieldName, e.getMessage());
        }
        return null;
    }

    /**
     * Filters service capabilities to only include those for services present in the final services list.
     * This ensures that when services are dropped during cloning, their associated capabilities are also removed.
     *
     * @param sourceServiceCapabilityList Capability list in indexed format: key = "0"/"1"/...,
     *                                    value = Map {"service": name, "capabilitytype": type, "capabilityvalue": val}
     * @param finalServices The list of service names that should be retained in the cloned offering
     * @return Filtered map containing only capabilities for services in finalServices
     */
    private Map<String, Map<String, String>> filterServiceCapabilities(Map<String, Map<String, String>> sourceServiceCapabilityList,
                                                                        List<String> finalServices) {
        Map<String, Map<String, String>> filteredCapabilities = new HashMap<>();

        int newIndex = 0;
        for (Map.Entry<String, Map<String, String>> entry : sourceServiceCapabilityList.entrySet()) {
            Map<String, String> capabilityEntry = entry.getValue();
            String serviceName = capabilityEntry.get(SERVICE);
            if (serviceName != null && finalServices.contains(serviceName)) {
                filteredCapabilities.put(String.valueOf(newIndex++), capabilityEntry);
            }
        }

        return filteredCapabilities;
    }

    private void validateConnectivtyServiceCapabilities(final Set<Provider> providers, final Map serviceCapabilitystList) {
        if (serviceCapabilitystList != null && !serviceCapabilitystList.isEmpty()) {
            final Collection serviceCapabilityCollection = serviceCapabilitystList.values();
            final Iterator iter = serviceCapabilityCollection.iterator();

            while (iter.hasNext()) {
                final HashMap<String, String> svcCapabilityMap = (HashMap<String, String>) iter.next();
                Capability capability = null;
                final String svc = svcCapabilityMap.get(SERVICE);
                final String capabilityName = svcCapabilityMap.get(CAPABILITYTYPE);
                final String capabilityValue = svcCapabilityMap.get(CAPABILITYVALUE);
                if (capabilityName != null) {
                    capability = Capability.getCapability(capabilityName);
                }

                if (capability == null || capabilityValue == null) {
                    throw new InvalidParameterValueException("Invalid capability:" + capabilityName + " capability value:" + capabilityValue);
                }
                final Service usedService = Service.getService(svc);

                checkCapabilityPerServiceProvider(providers, capability, usedService);

                if (!capabilityValue.equalsIgnoreCase(TRUE_VALUE) && !capabilityValue.equalsIgnoreCase(FALSE_VALUE)) {
                    throw new InvalidParameterValueException("Invalid Capability value:" + capabilityValue + " specified.");
                }
            }
        }
    }

    private boolean findCapabilityForService(final Map serviceCapabilitystList, final Capability capability, final Service service) {
        boolean foundCapability = false;
        if (serviceCapabilitystList != null && !serviceCapabilitystList.isEmpty()) {
            final Iterator iter = serviceCapabilitystList.values().iterator();
            while (iter.hasNext()) {
                final HashMap<String, String> currentCapabilityMap = (HashMap<String, String>) iter.next();
                final String currentCapabilityService = currentCapabilityMap.get(SERVICE);
                final String currentCapabilityName = currentCapabilityMap.get(CAPABILITYTYPE);
                final String currentCapabilityValue = currentCapabilityMap.get(CAPABILITYVALUE);

                if (currentCapabilityName == null || currentCapabilityService == null || currentCapabilityValue == null) {
                    throw new InvalidParameterValueException(String.format("Invalid capability with name %s, value %s and service %s", currentCapabilityName,
                            currentCapabilityValue, currentCapabilityService));
                }

                if (currentCapabilityName.equalsIgnoreCase(capability.getName())) {
                    foundCapability = currentCapabilityValue.equalsIgnoreCase(TRUE_VALUE);

                    if (!currentCapabilityService.equalsIgnoreCase(service.getName())) {
                        throw new InvalidParameterValueException(String.format("Invalid Service: %s specified. Capability %s can be specified only for service %s",
                                currentCapabilityService, service.getName(), currentCapabilityName));
                    }

                    break;
                }
            }
        }
        return foundCapability;
    }

    private boolean isVpcOfferingForRegionLevelVpc(final Map serviceCapabilitystList) {
        return findCapabilityForService(serviceCapabilitystList, Capability.RegionLevelVpc, Service.Connectivity);
    }

    private boolean isVpcOfferingSupportsDistributedRouter(final Map serviceCapabilitystList) {
        return findCapabilityForService(serviceCapabilitystList, Capability.DistributedRouter, Service.Connectivity);
    }

    private boolean isVpcOfferingRedundantRouter(final Map serviceCapabilitystList, Service redundantRouterService) {
        return findCapabilityForService(serviceCapabilitystList, Capability.RedundantRouter, redundantRouterService);
    }

    private VpcOffering updateVpcOfferingInternal(long vpcOffId, String vpcOfferingName, String displayText, String state, Integer sortKey, final List<Long> domainIds, final List<Long> zoneIds) {
        // Verify input parameters
        final VpcOfferingVO offeringToUpdate = _vpcOffDao.findById(vpcOffId);
        if (offeringToUpdate == null) {
            throw new InvalidParameterValueException("Unable to find vpc offering " + vpcOffId);
        }
        CallContext.current().setEventDetails(" ID: " + offeringToUpdate.getUuid());

        List<Long> existingDomainIds = vpcOfferingDetailsDao.findDomainIds(vpcOffId);
        Collections.sort(existingDomainIds);

        List<Long> existingZoneIds = vpcOfferingDetailsDao.findZoneIds(vpcOffId);
        Collections.sort(existingZoneIds);

        // Filter child domains when both parent and child domains are present
        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);
        Collections.sort(filteredDomainIds);

        List<Long> filteredZoneIds = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            filteredZoneIds.addAll(zoneIds);
        }
        Collections.sort(filteredZoneIds);

        final boolean updateNeeded = vpcOfferingName != null || displayText != null || state != null || sortKey != null;

        final VpcOfferingVO offering = _vpcOffDao.createForUpdate(vpcOffId);

        if (updateNeeded) {
            if (vpcOfferingName != null) {
                offering.setName(vpcOfferingName);
            }
            if (displayText != null) {
                offering.setDisplayText(displayText);
            }
            if (state != null) {
                boolean validState = false;
                for (final VpcOffering.State st : VpcOffering.State.values()) {
                    if (st.name().equalsIgnoreCase(state)) {
                        validState = true;
                        offering.setState(st);
                    }
                }
                if (!validState) {
                    throw new InvalidParameterValueException("Incorrect state value: " + state);
                }
            }
            if (sortKey != null) {
                offering.setSortKey(sortKey);
            }

            if (!_vpcOffDao.update(vpcOffId, offering)) {
                return null;
            }
        }
        List<VpcOfferingDetailsVO> detailsVO = new ArrayList<>();
        if (!filteredDomainIds.equals(existingDomainIds) || !filteredZoneIds.equals(existingZoneIds)) {
            SearchBuilder<VpcOfferingDetailsVO> sb = vpcOfferingDetailsDao.createSearchBuilder();
            sb.and("offeringId", sb.entity().getResourceId(), SearchCriteria.Op.EQ);
            sb.and("detailName", sb.entity().getName(), SearchCriteria.Op.EQ);
            sb.done();
            SearchCriteria<VpcOfferingDetailsVO> sc = sb.create();
            sc.setParameters("offeringId", String.valueOf(vpcOffId));
            if (!filteredDomainIds.equals(existingDomainIds)) {
                sc.setParameters("detailName", ApiConstants.DOMAIN_ID);
                vpcOfferingDetailsDao.remove(sc);
                for (Long domainId : filteredDomainIds) {
                    detailsVO.add(new VpcOfferingDetailsVO(vpcOffId, ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
                }
            }
            if (!filteredZoneIds.equals(existingZoneIds)) {
                sc.setParameters("detailName", ApiConstants.ZONE_ID);
                vpcOfferingDetailsDao.remove(sc);
                for (Long zoneId : filteredZoneIds) {
                    detailsVO.add(new VpcOfferingDetailsVO(vpcOffId, ApiConstants.ZONE_ID, String.valueOf(zoneId), false));
                }
            }
        }
        if (!detailsVO.isEmpty()) {
            for (VpcOfferingDetailsVO detailVO : detailsVO) {
                vpcOfferingDetailsDao.persist(detailVO);
            }
        }
        VpcOfferingVO updatedVpcOffering = _vpcOffDao.findById(vpcOffId);
        logger.debug("Updated VPC offering {}", updatedVpcOffering);
        return updatedVpcOffering;
    }
}
