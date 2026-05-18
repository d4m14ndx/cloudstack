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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypeImplementorsCmd;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.element.InternalLoadBalancerElementService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork.BroadcastDomainRange;
import com.cloud.network.VirtualRouterProvider.Type;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OvsProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.OvsProviderVO;
import com.cloud.network.element.VirtualRouterElement;
import com.cloud.network.element.VpcVirtualRouterElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.org.Grouping;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;

/**
 * Physical network lifecycle operations — extracted from {@link NetworkServiceImpl}.
 *
 * @see PhysicalNetworkManagementService
 */
@Component
public class PhysicalNetworkManagementServiceImpl implements PhysicalNetworkManagementService {

    protected Logger logger = LogManager.getLogger(getClass());

    private static final long MIN_VLAN_ID = 0L;
    private static final long MAX_VLAN_ID = 4095L; // 2^12 - 1
    private static final long MIN_GRE_KEY = 0L;
    private static final long MAX_GRE_KEY = 4294967295L; // 2^32 -1
    private static final long MIN_VXLAN_VNI = 0L;
    private static final long MAX_VXLAN_VNI = 16777214L; // 2^24 -2

    // Exclusive DAOs
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject
    PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Inject
    DataCenterVnetDao _dcVnetDao;
    @Inject
    OvsProviderDao _ovsProviderDao;

    // Shared DAOs (duplicated per playbook)
    @Inject
    DataCenterDao _dcDao;
    @Inject
    NetworkDao _networksDao;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    com.cloud.service.dao.ServiceOfferingDao serviceOfferingDao;
    @Inject
    AccountGuestVlanMapDao _accountGuestVlanMapDao;
    @Inject
    com.cloud.offerings.dao.NetworkOfferingDao _networkOfferingDao;

    // Collaborators
    @Inject
    NetworkModel _networkModel;
    @Inject
    AccountManager _accountMgr;
    @Inject
    StorageNetworkManager _stnwMgr;

    // InternalLB elements — set via setter from Spring XML (same pattern as god class)
    List<InternalLoadBalancerElementService> internalLoadBalancerElementServices = new ArrayList<>();
    Map<String, InternalLoadBalancerElementService> internalLoadBalancerElementServiceMap = new HashMap<>();

    // NetworkGurus — set via setter from Spring XML (same pattern as god class)
    List<NetworkGuru> _networkGurus;

    public void setNetworkGurus(List<NetworkGuru> networkGurus) {
        _networkGurus = networkGurus;
    }

    public void setInternalLoadBalancerElementServices(List<InternalLoadBalancerElementService> services) {
        this.internalLoadBalancerElementServices = services;
        if (MapUtils.isEmpty(internalLoadBalancerElementServiceMap) && CollectionUtils.isNotEmpty(services)) {
            for (InternalLoadBalancerElementService service : services) {
                internalLoadBalancerElementServiceMap.put(service.getProviderType().name(), service);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private void throwInvalidIdException(String message, String uuid, String description) {
        InvalidParameterValueException ex = new InvalidParameterValueException(message);
        ex.addProxyObject(uuid, description);
        throw ex;
    }

    private InternalLoadBalancerElementService getInternalLoadBalancerElementByNetworkServiceProviderId(long networkProviderId) {
        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(networkProviderId);
        if (provider == null) {
            String msg = String.format("Cannot find a network service provider with ID %s", networkProviderId);
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        Type type = provider.getProviderName().equalsIgnoreCase("nsx") ? Type.Nsx : Type.InternalLbVm;
        return internalLoadBalancerElementServiceMap.getOrDefault(type.name(), null);
    }

    // -----------------------------------------------------------------------
    // Public API methods
    // -----------------------------------------------------------------------

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", create = true)
    public PhysicalNetwork createPhysicalNetwork(final Long zoneId, final String vnetRange, final String networkSpeed, final List<String> isolationMethods, String broadcastDomainRangeStr,
            final Long domainId, final List<String> tags, final String name) {

        if (zoneId == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        if (Grouping.AllocationState.Enabled == zone.getAllocationState()) {
            throw new PermissionDeniedException(String.format("Cannot create PhysicalNetwork since the Zone is currently enabled, zone: %s", zone));
        }

        NetworkType zoneType = zone.getNetworkType();

        if (zoneType == NetworkType.Basic) {
            if (!_physicalNetworkDao.listByZone(zoneId).isEmpty()) {
                throw new CloudRuntimeException(String.format("Cannot add the physical network to basic zone: %s, there is a physical network already existing in this basic Zone", zone));
            }
        }
        if (tags != null && tags.size() > 1) {
            throw new InvalidParameterValueException("Only one tag can be specified for a physical network at this time");
        }

        if (isolationMethods != null && isolationMethods.size() > 1) {
            throw new InvalidParameterValueException("Only one isolationMethod can be specified for a physical network at this time");
        }

        if (vnetRange != null && zoneType == NetworkType.Basic) {
            throw new InvalidParameterValueException("Can't add vnet range to the physical network in the Basic zone");
        }

        BroadcastDomainRange broadcastDomainRange = null;
        if (broadcastDomainRangeStr != null && !broadcastDomainRangeStr.isEmpty()) {
            try {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.valueOf(broadcastDomainRangeStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve broadcastDomainRange '" + broadcastDomainRangeStr + "' to a supported value {Pod or Zone}");
            }

            if (zoneType == NetworkType.Basic && broadcastDomainRange != null && broadcastDomainRange != BroadcastDomainRange.POD) {
                throw new InvalidParameterValueException("Basic zone can have broadcast domain type of value " + BroadcastDomainRange.POD + " only");
            } else if (zoneType == NetworkType.Advanced && broadcastDomainRange != null && broadcastDomainRange != BroadcastDomainRange.ZONE) {
                throw new InvalidParameterValueException("Advance zone can have broadcast domain type of value " + BroadcastDomainRange.ZONE + " only");
            }
        }

        if (broadcastDomainRange == null) {
            if (zoneType == NetworkType.Basic) {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.POD;
            } else {
                broadcastDomainRange = PhysicalNetwork.BroadcastDomainRange.ZONE;
            }
        }

        try {
            final BroadcastDomainRange broadcastDomainRangeFinal = broadcastDomainRange;
            return Transaction.execute(new TransactionCallback<PhysicalNetworkVO>() {
                @Override
                public PhysicalNetworkVO doInTransaction(TransactionStatus status) {
                    long id = _physicalNetworkDao.getNextInSequence(Long.class, "id");
                    PhysicalNetworkVO pNetwork = new PhysicalNetworkVO(id, zoneId, vnetRange, networkSpeed, domainId, broadcastDomainRangeFinal, name);
                    pNetwork.setTags(tags);
                    pNetwork.setIsolationMethods(isolationMethods);

                    pNetwork = _physicalNetworkDao.persist(pNetwork);

                    if (vnetRange != null) {
                        addOrRemoveVnets(vnetRange.split(","), pNetwork);
                    }

                    addDefaultVirtualRouterToPhysicalNetwork(pNetwork.getId());

                    if (pNetwork.getIsolationMethods().contains("GRE")) {
                        addDefaultOvsToPhysicalNetwork(pNetwork.getId());
                    }

                    addDefaultSecurityGroupProviderToPhysicalNetwork(pNetwork.getId());
                    addDefaultVpcVirtualRouterToPhysicalNetwork(pNetwork.getId());
                    addDefaultBaremetalProvidersToPhysicalNetwork(pNetwork.getId());
                    addDefaultInternalLbProviderToPhysicalNetwork(pNetwork.getId());

                    try {
                        addDefaultTungstenProviderToPhysicalNetwork(pNetwork.getId());
                    } catch (Exception ex) {
                        logger.warn("Failed to add Tungsten provider to physical network due to:" + ex.getMessage());
                    }

                    addConfigDriveToPhysicalNetwork(pNetwork.getId());

                    try {
                        addNSXProviderToPhysicalNetwork(pNetwork.getId());
                    } catch (Exception ex) {
                        logger.warn("Failed to add NSX provider to physical network due to:", ex.getMessage());
                    }

                    try {
                        addNetrisProviderToPhysicalNetwork(pNetwork.getId());
                    } catch (Exception ex) {
                        logger.warn("Failed to add Netris provider to physical network due to:", ex.getMessage());
                    }

                    org.apache.cloudstack.context.CallContext.current().putContextParameter(PhysicalNetwork.class, pNetwork.getUuid());

                    return pNetwork;
                }
            });
        } catch (Exception ex) {
            logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to create a physical network");
        }
    }

    @Override
    public Pair<List<? extends PhysicalNetwork>, Integer> searchPhysicalNetworks(Long id, Long zoneId, String keyword, Long startIndex, Long pageSize, String name) {
        Filter searchFilter = new Filter(PhysicalNetworkVO.class, "id", Boolean.TRUE, startIndex, pageSize);
        SearchCriteria<PhysicalNetworkVO> sc = _physicalNetworkDao.createSearchCriteria();

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + name + "%");
        }

        if (keyword != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
        }

        Pair<List<PhysicalNetworkVO>, Integer> result = _physicalNetworkDao.searchAndCount(sc, searchFilter);
        return new Pair<List<? extends PhysicalNetwork>, Integer>(result.first(), result.second());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_UPDATE, eventDescription = "updating physical network", async = true)
    public PhysicalNetwork updatePhysicalNetwork(Long id, String networkSpeed, List<String> tags, String newVnetRange, String state) {

        PhysicalNetworkVO network = _physicalNetworkDao.findById(id);
        if (network == null) {
            throwInvalidIdException("Physical Network with specified id doesn't exist in the system", id.toString(), "physicalNetworkId");
        }

        DataCenter zone = _dcDao.findById(network.getDataCenterId());
        if (zone == null) {
            throwInvalidIdException("Zone with id=" + network.getDataCenterId() + " doesn't exist in the system", String.valueOf(network.getDataCenterId()), "dataCenterId");
        }

        if (newVnetRange != null && zone.getNetworkType() == NetworkType.Basic) {
            throw new InvalidParameterValueException("Can't add vnet range to the physical network in the Basic zone");
        }

        if (tags != null && tags.size() > 1) {
            throw new InvalidParameterValueException("Unable to support more than one tag on network yet");
        }

        if (tags != null && tags.size() == 0) {
            checkForPhysicalNetworksWithoutTag(network);
        }

        PhysicalNetwork.State networkState = null;
        if (state != null && !state.isEmpty()) {
            try {
                networkState = PhysicalNetwork.State.valueOf(state);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve state '" + state + "' to a supported value {Enabled or Disabled}");
            }
        }

        if (state != null) {
            network.setState(networkState);
        }

        if (tags != null) {
            network.setTags(tags);
        }

        if (networkSpeed != null) {
            network.setSpeed(networkSpeed);
        }

        if (newVnetRange != null) {
            String[] listOfRanges = newVnetRange.split(",");
            addOrRemoveVnets(listOfRanges, network);
        }
        _physicalNetworkDao.update(id, network);
        return network;
    }

    private void checkForPhysicalNetworksWithoutTag(PhysicalNetworkVO network) {
        Pair<List<PhysicalNetworkTrafficTypeVO>, Integer> result = _pNTrafficTypeDao.listAndCountBy(network.getId());
        if (result.second() > 0) {
            for (PhysicalNetworkTrafficTypeVO physicalNetworkTrafficTypeVO : result.first()) {
                TrafficType trafficType = physicalNetworkTrafficTypeVO.getTrafficType();
                checkForPhysicalNetworksWithoutTag(network, trafficType);
            }
        }
    }

    @Override
    @DB
    public void addOrRemoveVnets(String[] listOfRanges, final PhysicalNetworkVO network) {
        List<String> addVnets = null;
        List<String> removeVnets = null;
        HashSet<String> tempVnets = new HashSet<String>();
        HashSet<String> vnetsInDb = new HashSet<String>();
        List<Pair<Integer, Integer>> vnetranges = null;
        String commaSeparatedStringOfVnetRanges = null;
        int i = 0;
        if (listOfRanges.length != 0) {
            _physicalNetworkDao.acquireInLockTable(network.getId(), 10);
            vnetranges = validateVlanRange(network, listOfRanges);

            removeVnets = getVnetsToremove(network, vnetranges);

            vnetsInDb.addAll(_dcVnetDao.listVnetsByPhysicalNetworkAndDataCenter(network.getDataCenterId(), network.getId()));
            tempVnets.addAll(vnetsInDb);
            for (Pair<Integer, Integer> vlan : vnetranges) {
                for (i = vlan.first(); i <= vlan.second(); i++) {
                    tempVnets.add(Integer.toString(i));
                }
            }
            tempVnets.removeAll(vnetsInDb);

            if (removeVnets != null && removeVnets.size() != 0) {
                vnetsInDb.removeAll(removeVnets);
            }

            if (tempVnets.size() != 0) {
                addVnets = new ArrayList<String>();
                addVnets.addAll(tempVnets);
                vnetsInDb.addAll(tempVnets);
            }

            if (vnetsInDb.size() != 0) {
                commaSeparatedStringOfVnetRanges = generateVnetString(new ArrayList<String>(vnetsInDb));
            }
            network.setVnet(commaSeparatedStringOfVnetRanges);

            final List<String> addVnetsFinal = addVnets;
            final List<String> removeVnetsFinal = removeVnets;
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
                    if (addVnetsFinal != null) {
                        logger.debug("Adding vnet range {} for the physicalNetwork {} and zone {} as a part of updatePhysicalNetwork call", addVnetsFinal.toString(), network, zone);
                        _dcDao.addVnet(network.getDataCenterId(), network.getId(), addVnetsFinal);
                    }
                    if (removeVnetsFinal != null) {
                        logger.debug("removing vnet range {} for the physicalNetwork {} and zone {} as a part of updatePhysicalNetwork call", removeVnetsFinal.toString(), network, zone);
                        _dcVnetDao.deleteVnets(TransactionLegacy.currentTxn(), network.getDataCenterId(), network.getId(), removeVnetsFinal);
                    }
                    _physicalNetworkDao.update(network.getId(), network);
                }
            });

            _physicalNetworkDao.releaseFromLockTable(network.getId());
        }
    }

    private List<Pair<Integer, Integer>> validateVlanRange(PhysicalNetworkVO network, String[] listOfRanges) {
        Integer StartVnet;
        Integer EndVnet;
        List<Pair<Integer, Integer>> vlanTokens = new ArrayList<Pair<Integer, Integer>>();
        for (String vlanRange : listOfRanges) {
            String[] VnetRange = vlanRange.split("-");

            long minVnet = MIN_VLAN_ID;
            long maxVnet = MAX_VLAN_ID;

            logger.debug("ISOLATION METHODS:" + network.getIsolationMethods());
            if (network.getIsolationMethods().contains("GRE")) {
                minVnet = MIN_GRE_KEY;
                maxVnet = MAX_GRE_KEY;
            } else if (network.getIsolationMethods().contains("VXLAN") || network.getIsolationMethods().contains("Netris")) {
                minVnet = MIN_VXLAN_VNI;
                maxVnet = MAX_VXLAN_VNI;
                DataCenterVO zone = _dcDao.findById(network.getDataCenterId());
                for (String vnet : VnetRange) {
                    logger.debug("Looking to see if VNI {} already exists on another network in zone {}", vnet, zone);
                    List<DataCenterVnetVO> vnis = _dcVnetDao.findVnet(network.getDataCenterId(), vnet);
                    if (vnis != null && !vnis.isEmpty()) {
                        for (DataCenterVnetVO vni : vnis) {
                            if (vni.getPhysicalNetworkId() != network.getId()) {
                                logger.debug("VNI {} already exists on another network in zone ({}), please specify a unique range", vnet, zone);
                                throw new InvalidParameterValueException(String.format("VNI %s already exists on another network in zone (%s), please specify a unique range", vnet, zone));
                            }
                        }
                    }
                }
            }
            String rangeMessage = " between " + minVnet + " and " + maxVnet;
            if (VnetRange.length == 1 && VnetRange[0].equals("")) {
                return vlanTokens;
            }
            if (VnetRange.length < 2) {
                throw new InvalidParameterValueException("Please provide valid vnet range. vnet range should be a comma separated list of vlan ranges. example 500-500,600-601" + rangeMessage);
            }

            if (VnetRange[0] == null || VnetRange[1] == null) {
                throw new InvalidParameterValueException("Please provide valid vnet range" + rangeMessage);
            }

            try {
                StartVnet = Integer.parseInt(VnetRange[0]);
                EndVnet = Integer.parseInt(VnetRange[1]);
            } catch (NumberFormatException e) {
                logger.warn("Unable to parse vnet range:", e);
                throw new InvalidParameterValueException("Please provide valid vnet range. The vnet range should be a comma separated list example 2001-2012,3000-3005." + rangeMessage);
            }
            if (StartVnet < minVnet || EndVnet > maxVnet) {
                throw new InvalidParameterValueException("Vnet range has to be" + rangeMessage);
            }

            if (StartVnet > EndVnet) {
                throw new InvalidParameterValueException("Vnet range has to be" + rangeMessage + " and start range should be lesser than or equal to stop range");
            }
            vlanTokens.add(new Pair<Integer, Integer>(StartVnet, EndVnet));
        }
        return vlanTokens;
    }

    public String generateVnetString(List<String> vnetList) {
        Collections.sort(vnetList, new Comparator<String>() {
            @Override
            public int compare(String s1, String s2) {
                return Integer.valueOf(s1).compareTo(Integer.valueOf(s2));
            }
        });
        int i;
        String vnetRange = "";
        String startvnet = vnetList.get(0);
        String endvnet = "";
        for (i = 0; i < vnetList.size() - 1; i++) {
            if (Integer.parseInt(vnetList.get(i + 1)) - Integer.parseInt(vnetList.get(i)) > 1) {
                endvnet = vnetList.get(i);
                vnetRange = vnetRange + startvnet + "-" + endvnet + ",";
                startvnet = vnetList.get(i + 1);
            }
        }
        endvnet = vnetList.get(vnetList.size() - 1);
        vnetRange = vnetRange + startvnet + "-" + endvnet + ",";
        vnetRange = vnetRange.substring(0, vnetRange.length() - 1);
        return vnetRange;
    }

    private List<String> getVnetsToremove(PhysicalNetworkVO network, List<Pair<Integer, Integer>> vnetRanges) {
        int i;
        List<String> removeVnets = new ArrayList<String>();
        HashSet<String> vnetsInDb = new HashSet<String>();
        vnetsInDb.addAll(_dcVnetDao.listVnetsByPhysicalNetworkAndDataCenter(network.getDataCenterId(), network.getId()));
        if (vnetRanges.size() == 0) {
            removeVnets.addAll(vnetsInDb);
            int allocated_vnets = _dcVnetDao.countAllocatedVnets(network.getId());
            if (allocated_vnets > 0) {
                throw new InvalidParameterValueException(String.format("physicalnetwork %s has %d vnets in use", network, allocated_vnets));
            }
            return removeVnets;
        }
        for (Pair<Integer, Integer> vlan : vnetRanges) {
            for (i = vlan.first(); i <= vlan.second(); i++) {
                vnetsInDb.remove(Integer.toString(i));
            }
        }
        String vnetRange = null;
        if (vnetsInDb.size() != 0) {
            removeVnets.addAll(vnetsInDb);
            vnetRange = generateVnetString(removeVnets);
        } else {
            return removeVnets;
        }

        for (String vnet : vnetRange.split(",")) {
            String[] range = vnet.split("-");
            Integer start = Integer.parseInt(range[0]);
            Integer end = Integer.parseInt(range[1]);
            _dcVnetDao.lockRange(network.getDataCenterId(), network.getId(), start, end);
            List<DataCenterVnetVO> result = _dcVnetDao.listAllocatedVnetsInRange(network.getDataCenterId(), network.getId(), start, end);
            if (!result.isEmpty()) {
                throw new InvalidParameterValueException(String.format("physicalnetwork %s has allocated vnets in the range %d-%d", network, start, end));
            }
            List<AccountGuestVlanMapVO> maps = _accountGuestVlanMapDao.listAccountGuestVlanMapsByPhysicalNetwork(network.getId());
            for (AccountGuestVlanMapVO map : maps) {
                String[] vlans = map.getGuestVlanRange().split("-");
                Integer dedicatedStartVlan = Integer.parseInt(vlans[0]);
                Integer dedicatedEndVlan = Integer.parseInt(vlans[1]);
                if ((start >= dedicatedStartVlan && start <= dedicatedEndVlan) || (end >= dedicatedStartVlan && end <= dedicatedEndVlan)) {
                    throw new InvalidParameterValueException("Vnet range " + map.getGuestVlanRange() + " is dedicated" + " to an account. The specified range " + start + "-" + end
                            + " overlaps with the dedicated range " + " Please release the overlapping dedicated range before deleting the range");
                }
            }
        }
        return removeVnets;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_DELETE, eventDescription = "deleting physical network", async = true)
    @DB
    public boolean deletePhysicalNetwork(final Long physicalNetworkId) {

        PhysicalNetworkVO pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork == null) {
            throwInvalidIdException("Physical Network with specified id doesn't exist in the system", physicalNetworkId.toString(), "physicalNetworkId");
        }

        checkIfPhysicalNetworkIsDeletable(physicalNetworkId);

        return Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                disablePhysicalNetwork(physicalNetworkId, pNetwork);
                deleteIpAddresses();
                deleteVlans();
                deleteNetworks();

                _dcDao.deleteVnet(physicalNetworkId);

                if (!deleteProviders()) {
                    return false;
                }

                _pNTrafficTypeDao.deleteTrafficTypes(physicalNetworkId);

                return _physicalNetworkDao.remove(physicalNetworkId);
            }

            private void disablePhysicalNetwork(Long physicalNetworkId, PhysicalNetworkVO pNetwork) {
                pNetwork.setState(PhysicalNetwork.State.Disabled);
                _physicalNetworkDao.update(physicalNetworkId, pNetwork);
            }

            private void deleteIpAddresses() {
                List<IPAddressVO> ipAddresses = _ipAddressDao.listByPhysicalNetworkId(physicalNetworkId);
                for (IPAddressVO ipaddress : ipAddresses) {
                    _ipAddressDao.remove(ipaddress.getId());
                }
            }

            private boolean deleteProviders() {
                List<PhysicalNetworkServiceProviderVO> providers = _pNSPDao.listBy(physicalNetworkId);

                for (PhysicalNetworkServiceProviderVO provider : providers) {
                    try {
                        deleteNetworkServiceProvider(provider.getId());
                    } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                        logger.warn("Unable to complete destroy of the physical network provider: {}", provider, e);
                        return false;
                    }
                }
                return true;
            }

            private void deleteNetworks() {
                List<NetworkVO> networks = _networksDao.listByPhysicalNetwork(physicalNetworkId);
                if (CollectionUtils.isNotEmpty(networks)) {
                    for (NetworkVO network : networks) {
                        _networksDao.remove(network.getId());
                    }
                }
            }

            private void deleteVlans() {
                List<VlanVO> vlans = _vlanDao.listVlansByPhysicalNetworkId(physicalNetworkId);
                for (VlanVO vlan : vlans) {
                    _vlanDao.remove(vlan.getId());
                }
            }
        });
    }

    @DB
    void checkIfPhysicalNetworkIsDeletable(Long physicalNetworkId) {
        List<List<String>> tablesToCheck = new ArrayList<List<String>>();

        List<String> vnet = new ArrayList<String>();
        vnet.add(0, "op_dc_vnet_alloc");
        vnet.add(1, "physical_network_id");
        vnet.add(2, "there are allocated vnets for this physical network");
        tablesToCheck.add(vnet);

        List<String> networks = new ArrayList<String>();
        networks.add(0, "networks");
        networks.add(1, "physical_network_id");
        networks.add(2, "there are networks associated to this physical network");
        tablesToCheck.add(networks);

        List<String> publicIP = new ArrayList<String>();
        publicIP.add(0, "user_ip_address");
        publicIP.add(1, "physical_network_id");
        publicIP.add(2, "there are public IP addresses allocated for this physical network");
        tablesToCheck.add(publicIP);

        for (List<String> table : tablesToCheck) {
            String tableName = table.get(0);
            String column = table.get(1);
            String errorMsg = table.get(2);

            String dbName = "cloud";

            String selectSql = "SELECT * FROM `" + dbName + "`.`" + tableName + "` WHERE " + column + " = ?";

            if (tableName.equals("networks")) {
                selectSql += " AND removed is NULL";
            }

            if (tableName.equals("op_dc_vnet_alloc")) {
                selectSql += " AND taken IS NOT NULL";
            }

            if (tableName.equals("user_ip_address")) {
                selectSql += " AND state!='Free'";
            }

            if (tableName.equals("op_dc_ip_address_alloc")) {
                selectSql += " AND taken IS NOT NULL";
            }

            TransactionLegacy txn = TransactionLegacy.currentTxn();
            try {
                PreparedStatement stmt = txn.prepareAutoCloseStatement(selectSql);
                stmt.setLong(1, physicalNetworkId);
                ResultSet rs = stmt.executeQuery();
                if (rs != null && rs.next()) {
                    throw new CloudRuntimeException("The Physical Network is not deletable because " + errorMsg);
                }
            } catch (SQLException ex) {
                throw new CloudRuntimeException("The Management Server failed to detect if physical network is deletable. Please contact Cloud Support.");
            }
        }
    }

    @Override
    public List<? extends Service> listNetworkServices(String providerName) {

        Provider provider = null;
        if (providerName != null) {
            provider = Network.Provider.getProvider(providerName);
            if (provider == null) {
                throw new InvalidParameterValueException("Invalid Network Service Provider=" + providerName);
            }
        }

        if (provider != null) {
            NetworkElement element = _networkModel.getElementImplementingProvider(providerName);
            if (element == null) {
                throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + providerName + "'");
            }
            return new ArrayList<Service>(element.getCapabilities().keySet());
        } else {
            return Service.listAllServices();
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", create = true)
    public PhysicalNetworkServiceProvider addProviderToPhysicalNetwork(Long physicalNetworkId, String providerName, Long destinationPhysicalNetworkId, List<String> enabledServices) {

        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throwInvalidIdException("Physical Network with specified id doesn't exist in the system", physicalNetworkId.toString(), "physicalNetworkId");
        }

        if (destinationPhysicalNetworkId != null) {
            PhysicalNetworkVO destNetwork = _physicalNetworkDao.findById(destinationPhysicalNetworkId);
            if (destNetwork == null) {
                throwInvalidIdException("Destination Physical Network with specified id doesn't exist in the system", destinationPhysicalNetworkId.toString(), "destinationPhysicalNetworkId");
            }
        }

        if (providerName != null) {
            Provider provider = Network.Provider.getProvider(providerName);
            if (provider == null) {
                throw new InvalidParameterValueException("Invalid Network Service Provider=" + providerName);
            }
        }

        if (_pNSPDao.findByServiceProvider(physicalNetworkId, providerName) != null) {
            throw new CloudRuntimeException(String.format("The '%s' provider already exists on physical network : %s", providerName, network));
        }

        NetworkElement element = _networkModel.getElementImplementingProvider(providerName);
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + providerName + "'");
        }
        List<Service> services = new ArrayList<Service>();

        if (enabledServices != null) {
            if (!element.canEnableIndividualServices()) {
                if (enabledServices.size() != element.getCapabilities().keySet().size()) {
                    throw new InvalidParameterValueException("Cannot enable subset of Services, Please specify the complete list of Services for this Service Provider '" + providerName + "'");
                }
            }

            boolean addGatewayService = false;
            boolean isRoutedMode = enabledServices.stream().noneMatch(svc -> svc.equalsIgnoreCase(Service.SourceNat.getName()));
            for (String serviceName : enabledServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null || service == Service.Gateway) {
                    throw new InvalidParameterValueException("Invalid Network Service specified=" + serviceName);
                } else if (service == Service.SourceNat ||
                        (isRoutedMode && Arrays.asList(Provider.Nsx.getName().toLowerCase(Locale.ROOT),
                        Provider.Netris.getName().toLowerCase(Locale.ROOT)).contains(providerName.toLowerCase(Locale.ROOT)))) {
                    addGatewayService = true;
                }

                if (!element.getCapabilities().containsKey(service)) {
                    throw new InvalidParameterValueException(providerName + " Provider cannot provide this Service specified=" + serviceName);
                }
                services.add(service);
            }

            if (addGatewayService) {
                services.add(Service.Gateway);
            }
        } else {
            services = new ArrayList<Service>(element.getCapabilities().keySet());
        }

        try {
            PhysicalNetworkServiceProviderVO nsp = new PhysicalNetworkServiceProviderVO(physicalNetworkId, providerName);
            nsp.setEnabledServices(services);

            if (destinationPhysicalNetworkId != null) {
                nsp.setDestinationPhysicalNetworkId(destinationPhysicalNetworkId);
            }
            nsp = _pNSPDao.persist(nsp);

            return nsp;
        } catch (Exception ex) {
            logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to add a provider to physical network");
        }
    }

    @Override
    public Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> listNetworkServiceProviders(Long physicalNetworkId, String name, String state, Long startIndex, Long pageSize) {

        Filter searchFilter = new Filter(PhysicalNetworkServiceProviderVO.class, "id", false, startIndex, pageSize);
        SearchBuilder<PhysicalNetworkServiceProviderVO> sb = _pNSPDao.createSearchBuilder();
        SearchCriteria<PhysicalNetworkServiceProviderVO> sc = sb.create();

        if (physicalNetworkId != null) {
            sc.addAnd("physicalNetworkId", Op.EQ, physicalNetworkId);
        }

        if (name != null) {
            sc.addAnd("providerName", Op.EQ, name);
        }

        if (state != null) {
            sc.addAnd("state", Op.EQ, state);
        }

        Pair<List<PhysicalNetworkServiceProviderVO>, Integer> result = _pNSPDao.searchAndCount(sc, searchFilter);
        return new Pair<List<? extends PhysicalNetworkServiceProvider>, Integer>(result.first(), result.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_UPDATE, eventDescription = "Updating physical network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider updateNetworkServiceProvider(Long id, String stateStr, List<String> enabledServices)
            throws ResourceUnavailableException, ConcurrentOperationException {

        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(id);
        if (provider == null) {
            throw new InvalidParameterValueException("Network Service Provider id=" + id + "doesn't exist in the system");
        }

        NetworkElement element = _networkModel.getElementImplementingProvider(provider.getProviderName());
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getProviderName() + "'");
        }

        PhysicalNetworkServiceProvider.State state = null;
        if (stateStr != null && !stateStr.isEmpty()) {
            try {
                state = PhysicalNetworkServiceProvider.State.valueOf(stateStr);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve state '" + stateStr + "' to a supported value {Enabled or Disabled}");
            }
        }

        boolean update = false;

        if (state != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("trying to update the state of the service provider {} on physical network: {} to state: {}",
                        provider::toString, () -> _physicalNetworkDao.findById(provider.getPhysicalNetworkId()), stateStr::toString);
            }
            switch (state) {
            case Enabled:
                if (element != null && element.isReady(provider)) {
                    provider.setState(PhysicalNetworkServiceProvider.State.Enabled);
                    update = true;
                } else {
                    throw new CloudRuntimeException("Provider is not ready, cannot Enable the provider, please configure the provider first");
                }
                break;
            case Disabled:
                provider.setState(PhysicalNetworkServiceProvider.State.Disabled);
                update = true;
                break;
            case Shutdown:
                throw new InvalidParameterValueException("Updating the provider state to 'Shutdown' is not supported");
            }
        }

        if (enabledServices != null) {
            if (!element.canEnableIndividualServices()) {
                throw new InvalidParameterValueException("Cannot update set of Services for this Service Provider '" + provider.getProviderName() + "'");
            }

            List<Service> services = new ArrayList<Service>();
            for (String serviceName : enabledServices) {
                Network.Service service = Network.Service.getService(serviceName);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid Network Service specified=" + serviceName);
                }
                services.add(service);
            }
            provider.setEnabledServices(services);
            update = true;
        }

        if (update) {
            _pNSPDao.update(id, provider);
        }
        return provider;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_DELETE, eventDescription = "Deleting physical network ServiceProvider", async = true)
    public boolean deleteNetworkServiceProvider(Long id) throws ConcurrentOperationException, ResourceUnavailableException {
        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(id);

        if (provider == null) {
            throw new InvalidParameterValueException("Network Service Provider id=" + id + "doesn't exist in the system");
        }

        List<NetworkVO> networks = _networksDao.listByPhysicalNetworkAndProvider(provider.getPhysicalNetworkId(), provider.getProviderName());
        if (networks != null && !networks.isEmpty()) {
            throw new CloudRuntimeException("Provider is not deletable because there are active networks using this provider, please upgrade these networks to new network offerings");
        }

        User callerUser = _accountMgr.getActiveUser(org.apache.cloudstack.context.CallContext.current().getCallingUserId());
        Account callerAccount = _accountMgr.getActiveAccountById(callerUser.getAccountId());
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        if (logger.isDebugEnabled()) {
            logger.debug("Shutting down the service provider {} on physical network: {}",
                    provider::toString, () -> _physicalNetworkDao.findById(provider.getPhysicalNetworkId()));
        }
        NetworkElement element = _networkModel.getElementImplementingProvider(provider.getProviderName());
        if (element == null) {
            throw new InvalidParameterValueException("Unable to find the Network Element implementing the Service Provider '" + provider.getProviderName() + "'");
        }

        if (element != null && element.shutdownProviderInstances(provider, context)) {
            provider.setState(PhysicalNetworkServiceProvider.State.Shutdown);
        }

        return _pNSPDao.remove(id);
    }

    @Override
    public PhysicalNetwork getPhysicalNetwork(Long physicalNetworkId) {
        return _physicalNetworkDao.findById(physicalNetworkId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", async = true)
    public PhysicalNetwork getCreatedPhysicalNetwork(Long physicalNetworkId) {
        return getPhysicalNetwork(physicalNetworkId);
    }

    @Override
    public PhysicalNetworkServiceProvider getPhysicalNetworkServiceProvider(Long providerId) {
        return _pNSPDao.findById(providerId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider getCreatedPhysicalNetworkServiceProvider(Long providerId) {
        return getPhysicalNetworkServiceProvider(providerId);
    }

    @Override
    public long findPhysicalNetworkId(long zoneId, String tag, TrafficType trafficType) {
        return _networkModel.findPhysicalNetworkId(zoneId, tag, trafficType);
    }

    private void checkForPhysicalNetworksWithoutTag(PhysicalNetworkVO physicalNetwork, TrafficType trafficType) {
        int networkWithoutTagCount = 0;
        List<PhysicalNetworkVO> physicalNetworkVOList = _physicalNetworkDao.listByZoneAndTrafficType(physicalNetwork.getDataCenterId(), trafficType);

        for (PhysicalNetworkVO physicalNetworkVO : physicalNetworkVOList) {
            List<String> tags = physicalNetworkVO.getTags();
            if (CollectionUtils.isEmpty(tags)) {
                networkWithoutTagCount++;
            }
        }
        if (networkWithoutTagCount > 0) {
            logger.error("Number of physical networks without tags are " + networkWithoutTagCount);
            throw new CloudRuntimeException(String.format("There are more than 1 physical network without tags in the zone: %s",
                    _dcDao.findById(physicalNetwork.getDataCenterId())));
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", create = true)
    public PhysicalNetworkTrafficType addTrafficTypeToPhysicalNetwork(Long physicalNetworkId, String trafficTypeStr, String isolationMethod, String xenLabel, String kvmLabel, String vmwareLabel,
            String simulatorLabel, String vlan, String hypervLabel) {

        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throw new InvalidParameterValueException("Physical Network id=" + physicalNetworkId + "doesn't exist in the system");
        }

        Networks.TrafficType trafficType = null;
        if (trafficTypeStr != null && !trafficTypeStr.isEmpty()) {
            try {
                trafficType = Networks.TrafficType.valueOf(trafficTypeStr);
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("Unable to resolve trafficType '" + trafficTypeStr + "' to a supported value");
            }
        }

        if (_pNTrafficTypeDao.isTrafficTypeSupported(physicalNetworkId, trafficType)) {
            throw new CloudRuntimeException("This physical network already supports the traffic type: " + trafficType);
        }

        if (TrafficType.isSystemNetwork(trafficType) || TrafficType.Public.equals(trafficType) || TrafficType.Storage.equals(trafficType)) {
            if (!_physicalNetworkDao.listByZoneAndTrafficType(network.getDataCenterId(), trafficType).isEmpty()) {
                throw new CloudRuntimeException("Fail to add the traffic type to physical network because Zone already has a physical network with this traffic type: " + trafficType);
            }
        }

        if (TrafficType.Storage.equals(trafficType)) {
            List<com.cloud.vm.SecondaryStorageVmVO> ssvms = _stnwMgr.getSSVMWithNoStorageNetwork(network.getDataCenterId());
            if (!ssvms.isEmpty()) {
                StringBuilder sb = new StringBuilder("Cannot add " + trafficType
                        + " traffic type as there are below secondary storage vm still running. Please stop them all and add Storage traffic type again, then destroy them all to allow CloudStack recreate them with storage network(If you have added storage network ip range)");
                sb.append("SSVMs:");
                for (com.cloud.vm.SecondaryStorageVmVO ssvm : ssvms) {
                    sb.append(ssvm.getInstanceName()).append(":").append(ssvm.getState());
                }
                throw new CloudRuntimeException(sb.toString());
            }
        }

        List<String> tags = network.getTags();
        if (CollectionUtils.isEmpty(tags)) {
            checkForPhysicalNetworksWithoutTag(network, trafficType);
        }

        try {
            if (xenLabel == null) {
                xenLabel = getDefaultXenNetworkLabel(trafficType);
            }
            PhysicalNetworkTrafficTypeVO pNetworktrafficType = new PhysicalNetworkTrafficTypeVO(physicalNetworkId, trafficType, xenLabel, kvmLabel, vmwareLabel, simulatorLabel, vlan, hypervLabel,
                    null);
            pNetworktrafficType = _pNTrafficTypeDao.persist(pNetworktrafficType);

            if (TrafficType.Public.equals(trafficType)) {
                List<String> isolationMethods = network.getIsolationMethods();
                if ((isolationMethods.size() == 1 && isolationMethods.get(0).toLowerCase().equals("vxlan"))
                        || (isolationMethod != null && isolationMethods.contains(isolationMethod) && isolationMethod.toLowerCase().equals("vxlan"))) {
                    NetworkVO publicNetwork = _networksDao.listByZoneAndTrafficType(network.getDataCenterId(), TrafficType.Public).get(0);
                    if (publicNetwork != null) {
                        logger.debug("setting public network " + publicNetwork + " to broadcast type vxlan");
                        publicNetwork.setBroadcastDomainType(com.cloud.network.Networks.BroadcastDomainType.Vxlan);
                        _networksDao.persist(publicNetwork);
                    }
                }
            }

            return pNetworktrafficType;
        } catch (Exception ex) {
            logger.warn("Exception: ", ex);
            throw new CloudRuntimeException("Fail to add a traffic type to physical network");
        }
    }

    private String getDefaultXenNetworkLabel(TrafficType trafficType) {
        String xenLabel = null;
        switch (trafficType) {
        case Public:
            xenLabel = _configDao.getValue(Config.XenServerPublicNetwork.key());
            break;
        case Guest:
            xenLabel = _configDao.getValue(Config.XenServerGuestNetwork.key());
            break;
        case Storage:
            xenLabel = _configDao.getValue(Config.XenServerStorageNetwork1.key());
            break;
        case Management:
            xenLabel = _configDao.getValue(Config.XenServerPrivateNetwork.key());
            break;
        case Control:
            xenLabel = "cloud_link_local_network";
            break;
        case Vpn:
        case None:
            break;
        }
        return xenLabel;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", async = true)
    public PhysicalNetworkTrafficType getPhysicalNetworkTrafficType(Long id) {
        return _pNTrafficTypeDao.findById(id);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_UPDATE, eventDescription = "Updating physical network TrafficType", async = true)
    public PhysicalNetworkTrafficType updatePhysicalNetworkTrafficType(Long id, String xenLabel, String kvmLabel, String vmwareLabel, String hypervLabel) {

        PhysicalNetworkTrafficTypeVO trafficType = _pNTrafficTypeDao.findById(id);

        if (trafficType == null) {
            throw new InvalidParameterValueException("Traffic Type with id=" + id + "doesn't exist in the system");
        }

        if (xenLabel != null) {
            if ("".equals(xenLabel)) {
                xenLabel = null;
            }
            trafficType.setXenNetworkLabel(xenLabel);
        }
        if (kvmLabel != null) {
            if ("".equals(kvmLabel)) {
                kvmLabel = null;
            }
            trafficType.setKvmNetworkLabel(kvmLabel);
        }
        if (vmwareLabel != null) {
            if ("".equals(vmwareLabel)) {
                vmwareLabel = null;
            }
            trafficType.setVmwareNetworkLabel(vmwareLabel);
        }

        if (hypervLabel != null) {
            if ("".equals(hypervLabel)) {
                hypervLabel = null;
            }
            trafficType.setHypervNetworkLabel(hypervLabel);
        }

        _pNTrafficTypeDao.update(id, trafficType);
        return trafficType;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_DELETE, eventDescription = "Deleting physical network TrafficType", async = true)
    public boolean deletePhysicalNetworkTrafficType(Long id) {
        PhysicalNetworkTrafficTypeVO trafficType = _pNTrafficTypeDao.findById(id);

        if (trafficType == null) {
            throw new InvalidParameterValueException("Traffic Type with id=" + id + "doesn't exist in the system");
        }

        if (TrafficType.Guest.equals(trafficType.getTrafficType())) {
            if (!_networksDao.listByPhysicalNetworkTrafficType(trafficType.getPhysicalNetworkId(), trafficType.getTrafficType()).isEmpty()) {
                throw new CloudRuntimeException("The Traffic Type is not deletable because there are existing networks with this traffic type:" + trafficType.getTrafficType());
            }
        } else if (TrafficType.Storage.equals(trafficType.getTrafficType())) {
            PhysicalNetworkVO pn = _physicalNetworkDao.findById(trafficType.getPhysicalNetworkId());
            if (_stnwMgr.isAnyStorageIpInUseInZone(pn.getDataCenterId())) {
                throw new CloudRuntimeException("The Traffic Type is not deletable because there are still some storage network IP addresses in use:" + trafficType.getTrafficType());
            }
        }
        return _pNTrafficTypeDao.remove(id);
    }

    @Override
    public Pair<List<? extends PhysicalNetworkTrafficType>, Integer> listTrafficTypes(Long physicalNetworkId) {
        PhysicalNetworkVO network = _physicalNetworkDao.findById(physicalNetworkId);
        if (network == null) {
            throwInvalidIdException("Physical Network with specified id doesn't exist in the system", physicalNetworkId.toString(), "physicalNetworkId");
        }

        Pair<List<PhysicalNetworkTrafficTypeVO>, Integer> result = _pNTrafficTypeDao.listAndCountBy(physicalNetworkId);
        return new Pair<List<? extends PhysicalNetworkTrafficType>, Integer>(result.first(), result.second());
    }

    @Override
    public List<Pair<TrafficType, String>> listTrafficTypeImplementor(ListTrafficTypeImplementorsCmd cmd) {
        String type = cmd.getTrafficType();
        List<Pair<TrafficType, String>> results = new ArrayList<Pair<TrafficType, String>>();
        if (type != null) {
            for (NetworkGuru guru : _networkGurus) {
                if (guru.isMyTrafficType(TrafficType.getTrafficType(type))) {
                    results.add(new Pair<TrafficType, String>(TrafficType.getTrafficType(type), guru.getName()));
                    break;
                }
            }
        } else {
            for (NetworkGuru guru : _networkGurus) {
                TrafficType[] allTypes = guru.getSupportedTrafficType();
                for (TrafficType t : allTypes) {
                    results.add(new Pair<TrafficType, String>(t, guru.getName()));
                }
            }
        }

        return results;
    }

    // -----------------------------------------------------------------------
    // addDefault* helpers (interface-exposed for god-class delegates)
    // -----------------------------------------------------------------------

    @Override
    public PhysicalNetworkServiceProvider addDefaultVirtualRouterToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.VirtualRouter.getName(), null, null);
        NetworkElement networkElement = _networkModel.getElementImplementingProvider(Network.Provider.VirtualRouter.getName());
        if (networkElement == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the VirtualRouter Provider");
        }

        VirtualRouterElement element = (VirtualRouterElement)networkElement;
        element.addElement(nsp.getId(), Type.VirtualRouter);

        return nsp;
    }

    private PhysicalNetworkServiceProvider addDefaultOvsToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.Ovs.getName(), null, null);
        NetworkElement networkElement = _networkModel.getElementImplementingProvider(Network.Provider.Ovs.getName());
        if (networkElement == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the Ovs Provider");
        }
        OvsProviderVO element = _ovsProviderDao.findByNspId(nsp.getId());
        if (element != null) {
            logger.debug("There is already a Ovs element with service provider {}", nsp);
            return nsp;
        }
        element = new OvsProviderVO(nsp.getId());
        _ovsProviderDao.persist(element);
        return nsp;
    }

    @Override
    public PhysicalNetworkServiceProvider addDefaultVpcVirtualRouterToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.VPCVirtualRouter.getName(), null, null);

        NetworkElement networkElement = _networkModel.getElementImplementingProvider(Network.Provider.VPCVirtualRouter.getName());
        if (networkElement == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the VPCVirtualRouter Provider");
        }

        VpcVirtualRouterElement element = (VpcVirtualRouterElement)networkElement;
        element.addElement(nsp.getId(), Type.VPCVirtualRouter);

        return nsp;
    }

    @Override
    public PhysicalNetworkServiceProvider addDefaultInternalLbProviderToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.InternalLbVm.getName(), null, null);

        NetworkElement networkElement = _networkModel.getElementImplementingProvider(Network.Provider.InternalLbVm.getName());
        if (networkElement == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the " + Network.Provider.InternalLbVm.getName() + " Provider");
        }

        InternalLoadBalancerElementService service = getInternalLoadBalancerElementByNetworkServiceProviderId(nsp.getId());
        service.addInternalLoadBalancerElement(nsp.getId());

        return nsp;
    }

    private PhysicalNetworkServiceProvider addDefaultTungstenProviderToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.Tungsten.getName(), null, null);

        NetworkElement networkElement = _networkModel.getElementImplementingProvider(Network.Provider.Tungsten.getName());
        if (networkElement == null) {
            throw new CloudRuntimeException("Unable to find the Network Element implementing the " + Provider.Tungsten.getName() + " Provider");
        }
        return nsp;
    }

    @Override
    public PhysicalNetworkServiceProvider addDefaultSecurityGroupProviderToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkServiceProvider nsp = addProviderToPhysicalNetwork(physicalNetworkId, Network.Provider.SecurityGroupProvider.getName(), null, null);
        return nsp;
    }

    private PhysicalNetworkServiceProvider addDefaultBaremetalProvidersToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkVO pvo = _physicalNetworkDao.findById(physicalNetworkId);
        DataCenterVO dvo = _dcDao.findById(pvo.getDataCenterId());
        if (dvo.getNetworkType() == NetworkType.Basic) {

            Provider provider = Network.Provider.getProvider("BaremetalDhcpProvider");
            if (provider == null) {
                return null;
            }

            addProviderToPhysicalNetwork(physicalNetworkId, "BaremetalDhcpProvider", null, null);
            addProviderToPhysicalNetwork(physicalNetworkId, "BaremetalPxeProvider", null, null);
            addProviderToPhysicalNetwork(physicalNetworkId, "BaremetalUserdataProvider", null, null);
        } else if (dvo.getNetworkType() == NetworkType.Advanced) {
            addProviderToPhysicalNetwork(physicalNetworkId, "BaremetalPxeProvider", null, null);
            enableProvider("BaremetalPxeProvider");
        }

        return null;
    }

    private void enableProvider(String providerName) {
        QueryBuilder<PhysicalNetworkServiceProviderVO> q = QueryBuilder.create(PhysicalNetworkServiceProviderVO.class);
        q.and(q.entity().getProviderName(), SearchCriteria.Op.EQ, providerName);
        PhysicalNetworkServiceProviderVO provider = q.find();
        provider.setState(PhysicalNetworkServiceProvider.State.Enabled);
        _pNSPDao.update(provider.getId(), provider);
    }

    private PhysicalNetworkServiceProvider addConfigDriveToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkVO pvo = _physicalNetworkDao.findById(physicalNetworkId);
        DataCenterVO dvo = _dcDao.findById(pvo.getDataCenterId());
        if (dvo.getNetworkType() == NetworkType.Advanced) {

            Provider provider = Network.Provider.getProvider("ConfigDrive");
            if (provider == null) {
                return null;
            }

            addProviderToPhysicalNetwork(physicalNetworkId, Provider.ConfigDrive.getName(), null, null);
            enableProvider(Provider.ConfigDrive.getName());
        }
        return null;
    }

    private PhysicalNetworkServiceProvider addNSXProviderToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkVO pvo = _physicalNetworkDao.findById(physicalNetworkId);
        DataCenterVO dvo = _dcDao.findById(pvo.getDataCenterId());
        if (dvo.getNetworkType() == NetworkType.Advanced) {

            Provider provider = Network.Provider.getProvider(Provider.Nsx.getName());
            if (provider == null) {
                return null;
            }

            addProviderToPhysicalNetwork(physicalNetworkId, Provider.Nsx.getName(), null, null);
        }
        return null;
    }

    private PhysicalNetworkServiceProvider addNetrisProviderToPhysicalNetwork(long physicalNetworkId) {
        PhysicalNetworkVO pvo = _physicalNetworkDao.findById(physicalNetworkId);
        DataCenterVO dvo = _dcDao.findById(pvo.getDataCenterId());
        if (dvo.getNetworkType() == NetworkType.Advanced) {

            Provider provider = Network.Provider.getProvider(Provider.Netris.getName());
            if (provider == null) {
                return null;
            }

            addProviderToPhysicalNetwork(physicalNetworkId, Provider.Netris.getName(), null, null);
            enableProvider(Provider.Netris.getName());
        }
        return null;
    }
}
