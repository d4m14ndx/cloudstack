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

import java.util.Collections;
import java.util.Date;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.address.ReleasePodIpCmdByAdmin;
import org.apache.cloudstack.api.response.AcquirePodIpCmdResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;

import com.cloud.configuration.Resource;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.AccountLimitException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;

/**
 * Default implementation of {@link IpAddressLifecycleService}. Extracted from
 * {@code NetworkServiceImpl} as part of the Phase 4 god-class decomposition
 * (slice 6).
 */
@Component
public class IpAddressLifecycleServiceImpl implements IpAddressLifecycleService {

    private static final Logger logger = LogManager.getLogger(IpAddressLifecycleServiceImpl.class);

    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected VlanDetailsDao vlanDetailsDao;
    @Inject
    protected AccountVlanMapDao accountVlanMapDao;
    @Inject
    protected DomainVlanMapDao domainVlanMapDao;
    @Inject
    protected ReservationDao reservationDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected VpcDao vpcDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected ResourceTagDao resourceTagDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected ResourceLimitService resourceLimitMgr;
    @Inject
    protected IpAddressManager ipAddrMgr;
    @Inject
    protected VpcManager vpcMgr;
    @Inject
    protected RulesManager rulesMgr;
    @Inject
    protected EntityManager entityMgr;

    // -------------------------------------------------------------------------
    // Allocate
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "allocating Ip", create = true)
    public IpAddress allocateIP(Account ipOwner, long zoneId, Long networkId, Boolean displayIp, String ipaddress)
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException {

        Account caller = CallContext.current().getCallingAccount();
        com.cloud.user.User callerUser = CallContext.current().getCallingUser();
        DataCenter zone = entityMgr.findById(DataCenter.class, zoneId);

        if (networkId != null) {
            Network network = networksDao.findById(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Invalid network id is given");
            }

            if (network.getGuestType() == GuestType.Shared) {
                if (zone == null) {
                    throw new InvalidParameterValueException("Invalid zone Id is given");
                }
                if (zone.getNetworkType() == NetworkType.Advanced) {
                    if (isSharedNetworkOfferingWithServices(network.getNetworkOfferingId())) {
                        accountMgr.checkAccess(caller, AccessType.UseEntry, false, network);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Associate IP address called by the User {} Account {}", callerUser, ipOwner);
                        }
                        return ipAddrMgr.allocateIp(ipOwner, false, caller, callerUser, zone, displayIp, ipaddress);
                    } else {
                        throw new InvalidParameterValueException("Associate IP address can only be called on the shared networks in the advanced zone"
                                + " with Firewall/Source Nat/Static Nat/Port Forwarding/Load balancing services enabled");
                    }
                }
            }
        } else {
            accountMgr.checkAccess(caller, null, false, ipOwner);
        }

        IpAddress address = ipAddrMgr.allocateIp(ipOwner, false, caller, callerUser, zone, displayIp, ipaddress);
        if (address != null) {
            CallContext.current().putContextParameter(IpAddress.class, address.getUuid());
        }
        return address;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PORTABLE_IP_ASSIGN, eventDescription = "allocating portable public Ip", create = true)
    public IpAddress allocatePortableIP(Account ipOwner, int regionId, Long zoneId, Long networkId, Long vpcId)
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException {

        Account caller = CallContext.current().getCallingAccount();
        DataCenter zone = entityMgr.findById(DataCenter.class, zoneId);

        if ((networkId == null && vpcId == null) || (networkId != null && vpcId != null)) {
            throw new InvalidParameterValueException("One of Network id or VPC is should be passed");
        }

        if (networkId != null) {
            Network network = networksDao.findById(networkId);
            if (network == null) {
                throw new InvalidParameterValueException("Invalid network id is given");
            }

            if (network.getGuestType() == GuestType.Shared) {
                if (zone == null) {
                    throw new InvalidParameterValueException("Invalid zone Id is given");
                }
                if (zone.getNetworkType() == NetworkType.Advanced) {
                    if (isSharedNetworkOfferingWithServices(network.getNetworkOfferingId())) {
                        accountMgr.checkAccess(caller, AccessType.UseEntry, false, network);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Associate IP address called by the User {} Account {}", CallContext.current().getCallingUser(), ipOwner);
                        }
                        return ipAddrMgr.allocatePortableIp(ipOwner, caller, zoneId, networkId, null);
                    } else {
                        throw new InvalidParameterValueException("Associate IP address can only be called on the shared networks in the advanced zone"
                                + " with Firewall/Source Nat/Static Nat/Port Forwarding/Load balancing services enabled");
                    }
                }
            }
        }

        if (vpcId != null) {
            Vpc vpc = vpcDao.findById(vpcId);
            if (vpc == null) {
                throw new InvalidParameterValueException("Invalid vpc id is given");
            }
        }

        accountMgr.checkAccess(caller, null, false, ipOwner);
        return ipAddrMgr.allocatePortableIp(ipOwner, caller, zoneId, null, null);
    }

    // -------------------------------------------------------------------------
    // Release portable
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PORTABLE_IP_RELEASE, eventDescription = "disassociating portable Ip", async = true)
    public boolean releasePortableIpAddress(long ipAddressId) {
        try {
            return releaseIpAddressInternal(ipAddressId);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Reserve
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_RESERVE, eventDescription = "reserving Ip", async = false)
    public IpAddress reserveIpAddress(Account account, Boolean displayIp, Long ipAddressId) throws ResourceAllocationException {
        IPAddressVO ipVO = ipAddressDao.findById(ipAddressId);
        if (ipVO == null) {
            throw new InvalidParameterValueException("Unable to find IP address by ID=" + ipAddressId);
        }
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, true, account);

        VlanVO vlan = vlanDao.findById(ipVO.getVlanId());
        if (!vlan.getVlanType().equals(VlanType.VirtualNetwork)) {
            throw new IllegalArgumentException("Only IP addresses that belong to a virtual network may be reserved.");
        }
        if (ipVO.isPortable()) {
            throw new InvalidParameterValueException("Unable to reserve a portable IP.");
        }
        if (State.Reserved.equals(ipVO.getState())) {
            if (account.getId() == ipVO.getAccountId()) {
                logger.info(String.format("IP address %s has already been reserved for Account %s", ipVO.getAddress(), account));
                return ipVO;
            }
            throw new InvalidParameterValueException("Unable to reserve a IP because it has already been reserved for another Account.");
        }
        if (!State.Free.equals(ipVO.getState())) {
            throw new InvalidParameterValueException("Unable to reserve a IP in " + ipVO.getState() + " state.");
        }
        Long ipDedicatedDomainId = getIpDedicatedDomainId(ipVO.getVlanId());
        if (ipDedicatedDomainId != null && !ipDedicatedDomainId.equals(account.getDomainId())) {
            throw new InvalidParameterValueException("Unable to reserve a IP because it is dedicated to another domain.");
        }
        Long ipDedicatedAccountId = getIpDedicatedAccountId(ipVO.getVlanId());
        if (ipDedicatedAccountId != null && !ipDedicatedAccountId.equals(account.getAccountId())) {
            throw new InvalidParameterValueException("Unable to reserve a IP because it is dedicated to another Account.");
        }

        long reservedIpAddressesAmount = ipDedicatedAccountId == null ? 1L : 0L;
        try (CheckedReservation publicIpAddressReservation = new CheckedReservation(account, Resource.ResourceType.public_ip, reservedIpAddressesAmount, reservationDao, resourceLimitMgr)) {
            ipVO.setAllocatedTime(new Date());
            ipVO.setAllocatedToAccountId(account.getAccountId());
            ipVO.setAllocatedInDomainId(account.getDomainId());
            ipVO.setState(State.Reserved);
            if (displayIp != null) {
                ipVO.setDisplay(displayIp);
            }
            ipVO = ipAddressDao.persist(ipVO);
            if (reservedIpAddressesAmount > 0) {
                resourceLimitMgr.incrementResourceCount(account.getId(), Resource.ResourceType.public_ip);
            }
            return ipVO;
        } catch (ResourceAllocationException ex) {
            logger.warn("Failed to allocate resource of type " + ex.getResourceType() + " for account " + account);
            throw new AccountLimitException("Maximum number of public IP addresses for account: " + account.getAccountName() + " has been exceeded.");
        }
    }

    @Override
    public IpAddress reserveIpAddressWithVlanDetail(Account account, DataCenter zone, Boolean displayIp, String vlanDetailKey)
            throws ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, true, account);

        VlanVO vlan = findOneVlanRangeMatchingVlanDetailKey(zone, vlanDetailKey);
        if (vlan == null) {
            String msg = String.format("Cannot find any vlan matching the detail key %s on zone %s", vlanDetailKey, zone.getName());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }

        List<IPAddressVO> freeIps = ipAddressDao.listByVlanIdAndState(vlan.getId(), State.Free);
        if (CollectionUtils.isEmpty(freeIps)) {
            String msg = String.format("Cannot find any free IP matching on the VLAN range %s on zone %s", vlan.getIpRange(), zone.getName());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }

        Collections.shuffle(freeIps);
        IPAddressVO selectedIp = freeIps.get(0);

        selectedIp.setAllocatedTime(new Date());
        selectedIp.setAllocatedToAccountId(account.getAccountId());
        selectedIp.setAllocatedInDomainId(account.getDomainId());
        selectedIp.setState(State.Reserved);
        if (displayIp != null) {
            selectedIp.setDisplay(displayIp);
        }
        selectedIp = ipAddressDao.persist(selectedIp);

        Long ipDedicatedAccountId = getIpDedicatedAccountId(selectedIp.getVlanId());
        if (ipDedicatedAccountId == null) {
            resourceLimitMgr.incrementResourceCount(account.getId(), Resource.ResourceType.public_ip);
        }

        return selectedIp;
    }

    // -------------------------------------------------------------------------
    // Release reserved / release (disassociate)
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_RELEASE, eventDescription = "releasing Reserved Ip", async = false)
    public boolean releaseReservedIpAddress(long ipAddressId) throws InsufficientAddressCapacityException {
        IPAddressVO ipVO = ipAddressDao.findById(ipAddressId);
        if (ipVO == null) {
            throw new InvalidParameterValueException("Unable to find IP address by ID=" + ipAddressId);
        }
        if (ipVO.isPortable()) {
            throw new InvalidParameterValueException("Unable to release a portable IP, please use disassociateIpAddress instead");
        }
        if (State.Allocated.equals(ipVO.getState())) {
            throw new InvalidParameterValueException("Unable to release a public IP in Allocated state, please use disassociateIpAddress instead");
        }
        return releaseIpAddressInternal(ipAddressId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_RELEASE, eventDescription = "disassociating Ip", async = true)
    public boolean releaseIpAddress(long ipAddressId) throws InsufficientAddressCapacityException {
        return releaseIpAddressInternal(ipAddressId);
    }

    @DB
    protected boolean releaseIpAddressInternal(long ipAddressId) throws InsufficientAddressCapacityException {
        Long userId = CallContext.current().getCallingUserId();
        Account caller = CallContext.current().getCallingAccount();

        IPAddressVO ipVO = ipAddressDao.findById(ipAddressId);
        if (ipVO == null) {
            throw new InvalidParameterValueException("Unable to find IP address by id");
        }

        if (ipVO.getAllocatedTime() == null) {
            logger.debug("IP address {} is not allocated, so do nothing.", ipVO);
            return true;
        }

        if (ipVO.getAllocatedToAccountId() != null) {
            accountMgr.checkAccess(caller, null, true, ipVO);
        }

        Network guestNetwork = null;
        final Long networkId = ipVO.getAssociatedWithNetworkId();
        if (networkId != null) {
            guestNetwork = networksDao.findById(networkId);
        }
        Vpc vpc = null;
        if (ipVO.getVpcId() != null) {
            vpc = vpcMgr.getActiveVpc(ipVO.getVpcId());
        }
        if (ipVO.isSourceNat() && ((guestNetwork != null && guestNetwork.getState() != Network.State.Allocated) || vpc != null)) {
            throw new IllegalArgumentException("IP address is used for source nat purposes and can not be disassociated.");
        }

        VlanVO vlan = vlanDao.findById(ipVO.getVlanId());
        if (!vlan.getVlanType().equals(VlanType.VirtualNetwork)) {
            throw new IllegalArgumentException("Only IP addresses that belong to a virtual network may be disassociated.");
        }

        if (ipVO.getSystem()) {
            throwInvalidIdException("Can't release system IP address with specified id", ipVO.getUuid(), "systemIpAddrId");
        }

        if (State.Reserved.equals(ipVO.getState())) {
            ipAddressDao.unassignIpAddress(ipVO.getId());
            Long ipDedicatedAccountId = getIpDedicatedAccountId(ipVO.getVlanId());
            if (ipDedicatedAccountId == null) {
                resourceLimitMgr.decrementResourceCount(ipVO.getAccountId(), Resource.ResourceType.public_ip);
            }
            return true;
        }

        boolean success = ipAddrMgr.disassociatePublicIpAddress(ipVO, userId, caller);

        if (success) {
            resourceTagDao.removeByIdAndType(ipAddressId, ResourceObjectType.PublicIpAddress);
            if (guestNetwork != null) {
                NetworkOffering offering = entityMgr.findById(NetworkOffering.class, guestNetwork.getNetworkOfferingId());
                Long vmId = ipVO.getAssociatedWithVmId();
                if (offering.isElasticIp() && vmId != null) {
                    rulesMgr.getSystemIpAndEnableStaticNatForVm(userVmDao.findById(vmId), true);
                    return true;
                }
            }
        } else {
            logger.warn("Failed to release public IP address {}", ipVO);
        }
        return success;
    }

    // -------------------------------------------------------------------------
    // Associate
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_ASSIGN, eventDescription = "associating Ip", async = true)
    public IpAddress associateIPToNetwork(long ipId, long networkId)
            throws InsufficientAddressCapacityException, ResourceAllocationException,
            ResourceUnavailableException, ConcurrentOperationException {

        Network network = networksDao.findById(networkId);
        if (network == null) {
            releaseIpAddress(ipId);
            throw new InvalidParameterValueException("Invalid network id is given");
        }

        if (network.getVpcId() != null) {
            releaseIpAddress(ipId);
            throw new InvalidParameterValueException("Can't assign ip to the network directly when network belongs"
                    + " to VPC.Specify vpcId to associate ip address to VPC");
        }
        IpAddress address = ipAddrMgr.associateIPToGuestNetwork(ipId, networkId, true);
        if (address != null) {
            CallContext.current().putContextParameter(IpAddress.class, address.getUuid());
        }
        return address;
    }

    // -------------------------------------------------------------------------
    // Get
    // -------------------------------------------------------------------------

    @Override
    public IpAddress getIp(long ipAddressId) {
        return ipAddressDao.findById(ipAddressId);
    }

    @Override
    public IpAddress getIp(String ipAddress) {
        return ipAddressDao.findByIp(ipAddress);
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NET_IP_UPDATE, eventDescription = "updating public ip address", async = true)
    public IpAddress updateIP(Long id, String customId, Boolean displayIp) {
        Account caller = CallContext.current().getCallingAccount();
        IPAddressVO ipVO = ipAddressDao.findById(id);
        if (ipVO == null) {
            throw new InvalidParameterValueException("Unable to find ip address by id");
        }

        if (ipVO.getAllocatedToAccountId() != null) {
            accountMgr.checkAccess(caller, null, true, ipVO);
        } else if (caller.getType() != Account.Type.ADMIN) {
            throw new PermissionDeniedException("Only Root admin can update non-allocated ip addresses");
        }

        if (customId != null) {
            ipVO.setUuid(customId);
        }

        if (displayIp != null) {
            ipVO.setDisplay(displayIp);
        }

        ipAddressDao.update(id, ipVO);
        return ipAddressDao.findById(id);
    }

    // -------------------------------------------------------------------------
    // Pod IP
    // -------------------------------------------------------------------------

    @Override
    public AcquirePodIpCmdResponse allocatePodIp(Account ipOwner, String zoneId, String podId)
            throws ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        DataCenter zone = entityMgr.findByUuid(DataCenter.class, zoneId);

        if (zone == null) {
            throw new InvalidParameterValueException("Invalid zone Id ");
        }
        if (accountMgr.checkAccessAndSpecifyAuthority(caller, zone.getId()) != zone.getId()) {
            throw new InvalidParameterValueException(String.format("Caller does not have permission for this Zone (%s)", zone));
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Associate IP address called by the user {} account {}", CallContext.current().getCallingUser(), ipOwner);
        }
        return ipAddrMgr.allocatePodIp(zoneId, podId);
    }

    @Override
    public boolean releasePodIp(ReleasePodIpCmdByAdmin ip) throws CloudRuntimeException {
        ipAddrMgr.releasePodIp(ip.getId());
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private VlanVO findOneVlanRangeMatchingVlanDetailKey(DataCenter zone, String vlanDetailKey) {
        List<VlanVO> zoneVlans = vlanDao.listByZone(zone.getId());
        for (VlanVO zoneVlan : zoneVlans) {
            VlanDetailsVO detail = vlanDetailsDao.findDetail(zoneVlan.getId(), vlanDetailKey);
            if (detail != null && detail.getValue().equalsIgnoreCase("true")) {
                logger.debug(String.format("Found the VLAN range %s is set for NSX on zone %s", zoneVlan.getIpRange(), zone.getName()));
                return zoneVlan;
            }
        }
        return null;
    }

    private Long getIpDedicatedAccountId(Long vlanId) {
        List<AccountVlanMapVO> accountVlanMaps = accountVlanMapDao.listAccountVlanMapsByVlan(vlanId);
        if (CollectionUtils.isNotEmpty(accountVlanMaps)) {
            return accountVlanMaps.get(0).getAccountId();
        }
        return null;
    }

    private Long getIpDedicatedDomainId(Long vlanId) {
        List<DomainVlanMapVO> domainVlanMaps = domainVlanMapDao.listDomainVlanMapsByVlan(vlanId);
        if (CollectionUtils.isNotEmpty(domainVlanMaps)) {
            return domainVlanMaps.get(0).getDomainId();
        }
        return null;
    }

    protected boolean isSharedNetworkOfferingWithServices(long networkOfferingId) {
        com.cloud.offerings.NetworkOfferingVO networkOffering = networkOfferingDao.findById(networkOfferingId);
        if ((networkOffering.getGuestType() == GuestType.Shared)
                && (areServicesSupportedByNetworkOffering(networkOfferingId, Service.SourceNat)
                || areServicesSupportedByNetworkOffering(networkOfferingId, Service.StaticNat)
                || areServicesSupportedByNetworkOffering(networkOfferingId, Service.Firewall)
                || areServicesSupportedByNetworkOffering(networkOfferingId, Service.PortForwarding)
                || areServicesSupportedByNetworkOffering(networkOfferingId, Service.Lb))) {
            return true;
        }
        return false;
    }

    protected boolean areServicesSupportedByNetworkOffering(long networkOfferingId, Service... services) {
        return networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(networkOfferingId, services);
    }

    private void throwInvalidIdException(String message, String uuid, String description) {
        InvalidParameterValueException ex = new InvalidParameterValueException(message);
        ex.addProxyObject(uuid, description);
        throw ex;
    }
}
