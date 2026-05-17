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

import static com.cloud.configuration.ConfigurationManager.AllowNonRFC1918CompliantIPs;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.affinity.AffinityGroup;
import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.zone.CreateZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.DeleteZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.UpdateZoneCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.capacity.dao.CapacityDao;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.element.NetrisProviderVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.org.Grouping;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Zone (DataCenter) CRUD — extracted from
 * {@link ConfigurationManagerImpl}.
 *
 * @see ZoneService
 */
@Component
public class ZoneServiceImpl implements ZoneService {

    protected static final Logger logger = LogManager.getLogger(ZoneServiceImpl.class);

    @Inject
    protected DataCenterDao _zoneDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    protected AccountDao _accountDao;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected HostPodDao _podDao;
    @Inject
    protected VolumeDao _volumeDao;
    @Inject
    protected VMInstanceDao _vmInstanceDao;
    @Inject
    protected IPAddressDao _publicIpAddressDao;
    @Inject
    protected DataCenterIpAddressDao _privateIpAddressDao;
    @Inject
    protected PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    protected PhysicalNetworkTrafficTypeDao _trafficTypeDao;
    @Inject
    protected ImageStoreDao _imageStoreDao;
    @Inject
    protected VlanDao _vlanDao;
    @Inject
    protected CapacityDao _capacityDao;
    @Inject
    protected DedicatedResourceDao _dedicatedDao;
    @Inject
    protected AffinityGroupDao _affinityGroupDao;
    @Inject
    protected AffinityGroupService _affinityGroupService;
    @Inject
    protected NetworkOfferingDao _networkOfferingDao;
    @Inject
    protected NetworkOrchestrationService _networkMgr;
    @Inject
    protected NetworkService _networkSvc;
    @Inject
    protected NetworkModel _networkModel;
    @Inject
    protected VMTemplateZoneDao templateZoneDao;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected NsxProviderDao nsxProviderDao;
    @Inject
    protected NetrisProviderDao netrisProviderDao;

    // ------------------------------------------------------------------
    // ZoneService contract
    // ------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_ZONE_CREATE, eventDescription = "creating zone", async = false)
    public DataCenter createZone(final CreateZoneCmd cmd) {
        // grab parameters from the command
        final Long userId = CallContext.current().getCallingUserId();
        final String zoneName = cmd.getZoneName();
        final String dns1 = cmd.getDns1();
        final String dns2 = cmd.getDns2();
        final String ip6Dns1 = cmd.getIp6Dns1();
        final String ip6Dns2 = cmd.getIp6Dns2();
        final String internalDns1 = cmd.getInternalDns1();
        final String internalDns2 = cmd.getInternalDns2();
        final String guestCidr = cmd.getGuestCidrAddress();
        final Long domainId = cmd.getDomainId();
        final String type = cmd.getNetworkType();
        Boolean isBasic = false;
        String allocationState = cmd.getAllocationState();
        final String networkDomain = cmd.getDomain();
        boolean isSecurityGroupEnabled = cmd.getSecuritygroupenabled();
        final boolean isLocalStorageEnabled = cmd.getLocalStorageEnabled();
        final boolean isEdge = cmd.isEdge();
        final List<String> storageAccessGroups = cmd.getStorageAccessGroups();

        if (allocationState == null) {
            allocationState = Grouping.AllocationState.Disabled.toString();
        }

        if (!type.equalsIgnoreCase(NetworkType.Basic.toString()) && !type.equalsIgnoreCase(NetworkType.Advanced.toString())) {
            throw new InvalidParameterValueException("Invalid zone type; only Advanced and Basic values are supported");
        } else if (type.equalsIgnoreCase(NetworkType.Basic.toString())) {
            isBasic = true;
        }

        final NetworkType zoneType = isBasic ? NetworkType.Basic : NetworkType.Advanced;

        // error out when the parameter specified for Basic zone
        if (zoneType == NetworkType.Basic && guestCidr != null) {
            throw new InvalidParameterValueException("guestCidrAddress parameter is not supported for Basic zone");
        }

        if (!NetworkType.Advanced.equals(zoneType) && isEdge) {
            throw new InvalidParameterValueException("Only advanced network type zones can be edge zones");
        }

        DomainVO domainVO = null;

        if (domainId != null) {
            domainVO = _domainDao.findById(domainId);
        }

        if (zoneType == NetworkType.Basic) {
            isSecurityGroupEnabled = true;
        }

        return createZone(userId, zoneName, dns1, dns2, internalDns1, internalDns2, guestCidr, domainVO != null ? domainVO.getName() : null, domainId, zoneType, allocationState,
                networkDomain, isSecurityGroupEnabled, isLocalStorageEnabled, ip6Dns1, ip6Dns2, isEdge, storageAccessGroups);
    }

    @Override
    @DB
    public DataCenterVO createZone(final long userId, final String zoneName, final String dns1, final String dns2,
                                   final String internalDns1, final String internalDns2, final String guestCidr, final String domain,
                                   final Long domainId, final NetworkType zoneType, final String allocationStateStr, final String networkDomain,
                                   final boolean isSecurityGroupEnabled, final boolean isLocalStorageEnabled,
                                   final String ip6Dns1, final String ip6Dns2, final boolean isEdge, List<String> storageAccessGroups) {

        // checking the following params outside checkzoneparams method as we do
        // not use these params for updatezone
        // hence the method below is generic to check for common params
        if (guestCidr != null && !NetUtils.validateGuestCidr(guestCidr, !AllowNonRFC1918CompliantIPs.value())) {
            throw new InvalidParameterValueException("Please enter a valid guest cidr");
        }

        // Validate network domain
        if (networkDomain != null) {
            if (!NetUtils.verifyDomainName(networkDomain)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }
        }

        checkZoneParameters(zoneName, dns1, dns2, internalDns1, internalDns2, true, domainId, allocationStateStr, ip6Dns1, ip6Dns2);

        final byte[] bytes = (zoneName + System.currentTimeMillis()).getBytes();
        final String zoneToken = UuidUtils.nameUUIDFromBytes(bytes).toString();

        // Create the new zone in the database
        final DataCenterVO zoneFinal = new DataCenterVO(zoneName, null, dns1, dns2, internalDns1, internalDns2, guestCidr, domain, domainId, zoneType, zoneToken, networkDomain,
                isSecurityGroupEnabled, isLocalStorageEnabled, ip6Dns1, ip6Dns2);
        if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
            final Grouping.AllocationState allocationState = Grouping.AllocationState.valueOf(allocationStateStr);
            zoneFinal.setAllocationState(allocationState);
        } else {
            // Zone will be disabled since 3.0. Admin should enable it after
            // physical network and providers setup.
            zoneFinal.setAllocationState(Grouping.AllocationState.Disabled);
        }
        zoneFinal.setType(isEdge ? DataCenter.Type.Edge : DataCenter.Type.Core);
        if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
            zoneFinal.setStorageAccessGroups(String.join(",", storageAccessGroups));
        }

        return Transaction.execute(new TransactionCallback<>() {
            @Override
            public DataCenterVO doInTransaction(final TransactionStatus status) {
                final DataCenterVO zone = _zoneDao.persist(zoneFinal);
                CallContext.current().putContextParameter(DataCenter.class, zone.getUuid());
                if (domainId != null) {
                    // zone is explicitly dedicated to this domain
                    // create affinity group associated and dedicate the zone.
                    final AffinityGroup group = createDedicatedAffinityGroup(null, domainId, null);
                    final DedicatedResourceVO dedicatedResource = new DedicatedResourceVO(zone.getId(), null, null, null, domainId, null, group.getId());
                    _dedicatedDao.persist(dedicatedResource);
                }

                // Create default system networks
                createDefaultSystemNetworks(zone.getId());

                return zone;
            }
        });
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_ZONE_EDIT, eventDescription = "editing zone", async = false)
    public DataCenter editZone(final UpdateZoneCmd cmd) {
        // Parameter validation as from execute() method in V1
        final Long zoneId = cmd.getId();
        String zoneName = cmd.getZoneName();
        String dns1 = cmd.getDns1();
        String dns2 = cmd.getDns2();
        String ip6Dns1 = cmd.getIp6Dns1();
        String ip6Dns2 = cmd.getIp6Dns2();
        String internalDns1 = cmd.getInternalDns1();
        String internalDns2 = cmd.getInternalDns2();
        String guestCidr = cmd.getGuestCidrAddress();
        final List<String> dnsSearchOrder = cmd.getDnsSearchOrder();
        final Boolean isPublic = cmd.isPublic();
        final String allocationStateStr = cmd.getAllocationState();
        final String dhcpProvider = cmd.getDhcpProvider();
        final Map<?, ?> detailsMap = cmd.getDetails();
        final String networkDomain = cmd.getDomain();
        final Boolean localStorageEnabled = cmd.getLocalStorageEnabled();

        final Map<String, String> newDetails = new HashMap<>();
        if (detailsMap != null) {
            final Collection<?> zoneDetailsCollection = detailsMap.values();
            final Iterator<?> iter = zoneDetailsCollection.iterator();
            while (iter.hasNext()) {
                final HashMap<?, ?> detail = (HashMap<?, ?>)iter.next();
                final String key = (String)detail.get("key");
                final String value = (String)detail.get("value");
                if (key == null || value == null) {
                    throw new InvalidParameterValueException(
                            "Invalid Zone Detail specified, fields 'key' and 'value' cannot be null, please specify details in the form:  details[0].key=XXX&details[0].value=YYY");
                }
                newDetails.put(key, value);
            }
        }

        // add the domain prefix list to details if not null
        if (dnsSearchOrder != null) {
            for (final String dom : dnsSearchOrder) {
                if (!NetUtils.verifyDomainName(dom)) {
                    throw new InvalidParameterValueException(
                            "Invalid network domain suffixes. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                    + "and the hyphen ('-'); can't start or end with \"-\"");
                }
            }
            newDetails.put(ZoneConfig.DnsSearchOrder.getName(), StringUtils.join(dnsSearchOrder, ","));
        }

        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("unable to find zone by id " + zoneId);
        }

        if (zoneName == null) {
            zoneName = zone.getName();
        }

        if (guestCidr != null && !NetUtils.validateGuestCidr(guestCidr, !AllowNonRFC1918CompliantIPs.value())) {
            throw new InvalidParameterValueException("Please enter a valid guest cidr");
        }

        final String oldZoneName = zone.getName();

        if (zoneName == null) {
            zoneName = oldZoneName;
        }

        if (dns1 == null) {
            dns1 = zone.getDns1();
        }

        if (dns2 == null) {
            dns2 = zone.getDns2();
        }

        if (ip6Dns1 == null) {
            ip6Dns1 = zone.getIp6Dns1();
        }

        if (ip6Dns2 == null) {
            ip6Dns2 = zone.getIp6Dns2();
        }

        if (internalDns1 == null) {
            internalDns1 = zone.getInternalDns1();
        }

        if (internalDns2 == null) {
            internalDns2 = zone.getInternalDns2();
        }

        if (guestCidr == null) {
            guestCidr = zone.getGuestNetworkCidr();
        }

        int sortKey = cmd.getSortKey() != null ? cmd.getSortKey() : zone.getSortKey();

        // validate network domain
        if (networkDomain != null && !networkDomain.isEmpty()) {
            if (!NetUtils.verifyDomainName(networkDomain)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }
        }

        final boolean checkForDuplicates = !zoneName.equals(oldZoneName);
        checkZoneParameters(zoneName, dns1, dns2, internalDns1, internalDns2, checkForDuplicates, null, allocationStateStr, ip6Dns1, ip6Dns2);// not allowing updating
        // domain associated with
        // a zone, once created

        zone.setName(zoneName);
        zone.setDns1(dns1);
        zone.setDns2(dns2);
        zone.setIp6Dns1(ip6Dns1);
        zone.setIp6Dns2(ip6Dns2);
        zone.setInternalDns1(internalDns1);
        zone.setInternalDns2(internalDns2);
        zone.setGuestNetworkCidr(guestCidr);
        zone.setSortKey(sortKey);
        if (localStorageEnabled != null) {
            zone.setLocalStorageEnabled(localStorageEnabled.booleanValue());
        }

        if (networkDomain != null) {
            if (networkDomain.isEmpty()) {
                zone.setDomain(null);
            } else {
                zone.setDomain(networkDomain);
            }
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                final Map<String, String> updatedDetails = new HashMap<>();
                _zoneDao.loadDetails(zone);
                if (zone.getDetails() != null) {
                    updatedDetails.putAll(zone.getDetails());
                }
                updatedDetails.putAll(newDetails);
                zone.setDetails(updatedDetails);

                if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
                    final Grouping.AllocationState allocationState = Grouping.AllocationState.valueOf(allocationStateStr);

                    if (allocationState == Grouping.AllocationState.Enabled && !DataCenter.Type.Edge.equals(zone.getType())) {
                        // check if zone has necessary trafficTypes before enabling
                        try {
                            PhysicalNetwork mgmtPhyNetwork;
                            // zone should have a physical network with management
                            // traffiType
                            mgmtPhyNetwork = _networkModel.getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Management);
                            if (NetworkType.Advanced == zone.getNetworkType() && !zone.isSecurityGroupEnabled()) {
                                // advanced zone without SG should have a physical
                                // network with public Type
                                _networkModel.getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Public);
                            }

                            try {
                                _networkModel.getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Storage);
                            } catch (final InvalidParameterValueException noStorage) {
                                final PhysicalNetworkTrafficTypeVO mgmtTraffic = _trafficTypeDao.findBy(mgmtPhyNetwork.getId(), TrafficType.Management);
                                _networkSvc.addTrafficTypeToPhysicalNetwork(mgmtPhyNetwork.getId(), TrafficType.Storage.toString(), "vlan", mgmtTraffic.getXenNetworkLabel(),
                                        mgmtTraffic.getKvmNetworkLabel(), mgmtTraffic.getVmwareNetworkLabel(), mgmtTraffic.getSimulatorNetworkLabel(), mgmtTraffic.getVlan(),
                                        mgmtTraffic.getHypervNetworkLabel());
                                logger.info("No storage traffic type was specified by admin, create default storage traffic on physical network {} with same configure of management traffic type", mgmtPhyNetwork);
                            }
                        } catch (final InvalidParameterValueException ex) {
                            throw new InvalidParameterValueException("Cannot enable this Zone since: " + ex.getMessage());
                        }
                    }
                    zone.setAllocationState(allocationState);
                }

                if (dhcpProvider != null) {
                    zone.setDhcpProvider(dhcpProvider);
                }

                // update a private zone to public; not vice versa
                if (isPublic != null && isPublic) {
                    zone.setDomainId(null);
                    zone.setDomain(null);

                    // release the dedication for this zone
                    final DedicatedResourceVO resource = _dedicatedDao.findByZoneId(zoneId);
                    Long resourceId = null;
                    if (resource != null) {
                        resourceId = resource.getId();
                        if (!_dedicatedDao.remove(resourceId)) {
                            throw new CloudRuntimeException(String.format("Failed to delete dedicated Zone Resource %s", resource));
                        }
                        // find the group associated and check if there are any more
                        // resources under that group
                        final List<DedicatedResourceVO> resourcesInGroup = _dedicatedDao.listByAffinityGroupId(resource.getAffinityGroupId());
                        if (resourcesInGroup.isEmpty()) {
                            // delete the group
                            _affinityGroupService.deleteAffinityGroup(resource.getAffinityGroupId(), null, null, null, null);
                        }
                    }
                }

                if (!_zoneDao.update(zoneId, zone)) {
                    throw new CloudRuntimeException("Failed to edit zone. Please contact Cloud Support.");
                }
            }
        });

        return zone;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_ZONE_DELETE, eventDescription = "deleting zone", async = false)
    public boolean deleteZone(final DeleteZoneCmd cmd) {

        final Long zoneId = cmd.getId();
        DataCenterVO zone = _zoneDao.findById(zoneId);

        // Make sure the zone exists
        if (!validZone(zoneId)) {
            throw new InvalidParameterValueException("A zone with ID: " + zoneId + " does not exist.");
        }

        checkIfZoneIsDeletable(zoneId);

        return Transaction.execute(new TransactionCallback<>() {
            @Override
            public Boolean doInTransaction(final TransactionStatus status) {
                // delete vlans for this zone
                final List<VlanVO> vlans = _vlanDao.listByZone(zoneId);
                for (final VlanVO vlan : vlans) {
                    _vlanDao.remove(vlan.getId());
                }
                // we should actually find the mapping and remove if it exists
                // but we don't know about vmware/plugin/hypervisors at this point
                final boolean success = _zoneDao.remove(zoneId);

                if (success) {
                    deleteExternalProviderIfAny(zoneId);

                    // delete template refs for this zone
                    templateZoneDao.deleteByZoneId(zoneId);
                    // delete all capacity records for the zone
                    _capacityDao.removeBy(null, zoneId, null, null, null);
                    // remove from dedicated resources
                    final DedicatedResourceVO dr = _dedicatedDao.findByZoneId(zoneId);
                    if (dr != null) {
                        _dedicatedDao.remove(dr.getId());
                        // find the group associated and check if there are any more
                        // resources under that group
                        final List<DedicatedResourceVO> resourcesInGroup = _dedicatedDao.listByAffinityGroupId(dr.getAffinityGroupId());
                        if (resourcesInGroup.isEmpty()) {
                            // delete the group
                            _affinityGroupService.deleteAffinityGroup(dr.getAffinityGroupId(), null, null, null, null);
                        }
                    }
                    annotationDao.removeByEntityType(AnnotationService.EntityType.ZONE.name(), zone.getUuid());
                }

                return success;
            }
        });
    }

    @Override
    public void createDefaultSystemNetworks(final long zoneId) throws ConcurrentOperationException {
        final DataCenterVO zone = _zoneDao.findById(zoneId);
        final String networkDomain = null;
        // Create public, management, control and storage networks as a part of
        // the zone creation
        if (zone != null) {
            final List<NetworkOfferingVO> ntwkOff = _networkOfferingDao.listSystemNetworkOfferings();

            for (final NetworkOfferingVO offering : ntwkOff) {
                final DataCenterDeployment plan = new DataCenterDeployment(zone.getId(), null, null, null, null, null);
                final NetworkVO userNetwork = new NetworkVO();

                final Account systemAccount = _accountDao.findById(Account.ACCOUNT_ID_SYSTEM);

                BroadcastDomainType broadcastDomainType = null;
                if (offering.getTrafficType() == TrafficType.Management) {
                    broadcastDomainType = BroadcastDomainType.Native;
                } else if (offering.getTrafficType() == TrafficType.Control) {
                    broadcastDomainType = BroadcastDomainType.LinkLocal;
                } else if (offering.getTrafficType() == TrafficType.Public) {
                    if (zone.getNetworkType() == NetworkType.Advanced && !zone.isSecurityGroupEnabled() || zone.getNetworkType() == NetworkType.Basic) {
                        broadcastDomainType = BroadcastDomainType.Vlan;
                    } else {
                        continue; // so broadcastDomainType remains null! why have None/Undecided/UnKnown?
                    }
                } else if (offering.getTrafficType() == TrafficType.Guest) {
                    continue;
                }

                userNetwork.setBroadcastDomainType(broadcastDomainType);
                userNetwork.setNetworkDomain(networkDomain);
                _networkMgr.setupNetwork(systemAccount, offering, userNetwork, plan, null, null, false, Domain.ROOT_DOMAIN, null, null, null, true);
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers — duplicated from ConfigurationManagerImpl so the
    // slice has no back-reference into the manager. The manager keeps its
    // own copies because other paths (e.g. cluster / pod deletability,
    // service-offering creation, network-offering creation) still invoke
    // them directly.
    // ------------------------------------------------------------------

    protected void checkZoneParameters(final String zoneName, final String dns1, final String dns2, final String internalDns1, final String internalDns2, final boolean checkForDuplicates, final Long domainId,
            final String allocationStateStr, final String ip6Dns1, final String ip6Dns2) {
        if (checkForDuplicates) {
            // Check if a zone with the specified name already exists
            if (validZone(zoneName)) {
                throw new InvalidParameterValueException("A zone with that name already exists. Please specify a unique zone name.");
            }
        }

        // check if valid domain
        if (domainId != null) {
            final DomainVO domain = _domainDao.findById(domainId);

            if (domain == null) {
                throw new InvalidParameterValueException("Please specify a valid domain id");
            }
        }

        // Check IP validity for DNS addresses
        // Empty strings is a valid input -- hence the length check
        if (dns1 != null && dns1.length() > 0 && !NetUtils.isValidIp4(dns1)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for DNS1");
        }

        if (dns2 != null && dns2.length() > 0 && !NetUtils.isValidIp4(dns2)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for DNS2");
        }

        if (internalDns1 != null && internalDns1.length() > 0 && !NetUtils.isValidIp4(internalDns1)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for internal DNS1");
        }

        if (internalDns2 != null && internalDns2.length() > 0 && !NetUtils.isValidIp4(internalDns2)) {
            throw new InvalidParameterValueException("Please enter a valid IP address for internal DNS2");
        }

        if (ip6Dns1 != null && ip6Dns1.length() > 0 && !NetUtils.isValidIp6(ip6Dns1)) {
            throw new InvalidParameterValueException("Please enter a valid IPv6 address for IP6 DNS1");
        }

        if (ip6Dns2 != null && ip6Dns2.length() > 0 && !NetUtils.isValidIp6(ip6Dns2)) {
            throw new InvalidParameterValueException("Please enter a valid IPv6 address for IP6 DNS2");
        }

        if (allocationStateStr != null && !allocationStateStr.isEmpty()) {
            try {
                Grouping.AllocationState.valueOf(allocationStateStr);
            } catch (final IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve Allocation State '" + allocationStateStr + "' to a supported state");
            }
        }
    }

    @DB
    protected void checkIfZoneIsDeletable(final long zoneId) {
        final String errorMsg = "The zone cannot be deleted because ";


        // Check if there are any non-removed hosts in the zone.
        if (!_hostDao.listEnabledIdsByDataCenterId(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are servers in this zone.");
        }

        // Check if there are any non-removed pods in the zone.
        if (!_podDao.listByDataCenterId(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are pods in this zone.");
        }

        // Check if there are allocated private IP addresses in the zone.
        if (_privateIpAddressDao.countIPs(zoneId, true) != 0) {
            throw new CloudRuntimeException(errorMsg + "there are private IP addresses allocated in this zone.");
        }

        // Check if there are allocated public IP addresses in the zone.
        if (_publicIpAddressDao.countIPs(zoneId, true) != 0) {
            throw new CloudRuntimeException(errorMsg + "there are public IP addresses allocated in this zone.");
        }

        // Check if there are any non-removed vms in the zone.
        if (!_vmInstanceDao.listByZoneId(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are virtual machines in this zone.");
        }

        // Check if there are any non-removed volumes in the zone.
        if (!_volumeDao.findByDc(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are storage volumes in this zone.");
        }

        // Check if there are any non-removed physical networks in the zone.
        if (!_physicalNetworkDao.listByZone(zoneId).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are physical networks in this zone.");
        }

        //check if there are any secondary stores attached to the zone
        if(!_imageStoreDao.findByZone(new ZoneScope(zoneId), null).isEmpty()) {
            throw new CloudRuntimeException(errorMsg + "there are Secondary storages in this zone");
        }
    }

    protected boolean validZone(final String zoneName) {
        return _zoneDao.findByName(zoneName) != null;
    }

    protected boolean validZone(final long zoneId) {
        return _zoneDao.findById(zoneId) != null;
    }

    protected void deleteExternalProviderIfAny(Long zoneId) {
        NsxProviderVO nsxProvider = nsxProviderDao.findByZoneId(zoneId);
        if (Objects.nonNull(nsxProvider)) {
            nsxProviderDao.remove(nsxProvider.getId());
        }
        NetrisProviderVO netrisProvider = netrisProviderDao.findByZoneId(zoneId);
        if (Objects.nonNull(netrisProvider)) {
            netrisProviderDao.remove(netrisProvider.getId());
        }
    }

    protected AffinityGroup createDedicatedAffinityGroup(String affinityGroupName, final Long domainId, final Long accountId) {
        if (affinityGroupName == null) {
            // default to a groupname with account/domain information
            affinityGroupName = "ZoneDedicatedGrp-domain-" + domainId + (accountId != null ? "-acct-" + accountId : "");
        }

        AffinityGroup group = null;
        String accountName = null;

        if (accountId != null) {
            final AccountVO account = _accountDao.findById(accountId);
            accountName = account.getAccountName();

            group = _affinityGroupDao.findByAccountAndName(accountId, affinityGroupName);
            if (group != null) {
                return group;
            }
        } else {
            // domain level group
            group = _affinityGroupDao.findDomainLevelGroupByName(domainId, affinityGroupName);
            if (group != null) {
                return group;
            }
        }

        group = _affinityGroupService.createAffinityGroup(accountName, null, domainId, affinityGroupName, "ExplicitDedication", "dedicated resources group");

        return group;

    }
}
