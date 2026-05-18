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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class NetworkMigrationServiceImpl implements NetworkMigrationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    NetworkDao _networksDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    ResourceTagDao _resourceTagDao;
    @Inject
    VpcDao _vpcDao;
    @Inject
    VMInstanceDao _vmDao;
    @Inject
    IPAddressDao _ipAddressDao;
    @Inject
    VlanDao _vlanDao;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    AccountManager _accountMgr;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    NetworkMigrationManager _networkMigrationManager;
    @Inject
    VpcOfferingDao _vpcOfferingDao;
    @Inject
    AccountService _accountService;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    NetworkModel _networkModel;

    @Override
    public Network migrateGuestNetwork(long networkId, long networkOfferingId, Account callerAccount, User callerUser, boolean resume) {
        NetworkVO network = _networksDao.findById(networkId);
        NetworkOffering newNtwkOff = _networkOfferingDao.findById(networkOfferingId);

        if (network.getVpcId() != null) {
            logger.warn("Failed to migrate network as the specified network is a vpc tier. Use migrateVpc.");
            throw new InvalidParameterValueException("Failed to migrate network as the specified network is a vpc tier. Use migrateVpc.");
        }

        if (_configMgr.isOfferingForVpc(newNtwkOff)) {
            logger.warn("Failed to migrate network as the specified network offering is a VPC offering");
            throw new InvalidParameterValueException("Failed to migrate network as the specified network offering is a VPC offering");
        }

        verifyNetworkCanBeMigrated(callerAccount, network);

        long newPhysicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), newNtwkOff.getTags(), newNtwkOff.getTrafficType());

        final long oldNetworkOfferingId = network.getNetworkOfferingId();
        NetworkOffering oldNtwkOff = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);

        if (!resume && network.getRelated() != network.getId()) {
            logger.warn("Related network is not equal to network id. You might want to re-run migration with resume = true command.");
            throw new CloudRuntimeException("Failed to migrate network as previous migration left this network in transient condition. Specify resume as true.");
        }

        if (networkNeedsMigration(network, newPhysicalNetworkId, oldNtwkOff, newNtwkOff)) {
            return migrateNetworkToPhysicalNetwork(network, oldNtwkOff, newNtwkOff, null, null, newPhysicalNetworkId, callerAccount, callerUser);
        } else {
            logger.info("Network does not need migration.");
            return network;
        }
    }

    @Override
    public Vpc migrateVpcNetwork(long vpcId, long vpcOfferingId, Map<String, String> networkToOffering, Account account, User callerUser, boolean resume) {
        ResourceTag relatedVpc = _resourceTagDao.findByKey(vpcId, ResourceObjectType.Vpc, NetworkMigrationManager.MIGRATION);
        long vpcCopyId = 0;

        if (relatedVpc != null) {
            if (resume) {
                vpcCopyId = vpcId;
                vpcId = Long.parseLong(relatedVpc.getValue());
                verifyAlreadyMigratedTiers(vpcCopyId, vpcOfferingId, networkToOffering);
            } else {
                logger.warn("This vpc has a migration row in the resource details table. You might want to re-run migration with resume = true command.");
                throw new CloudRuntimeException("Failed to migrate VPC as previous migration left this VPC in transient condition. Specify resume as true.");
            }
        }

        Vpc vpc = _vpcDao.findById(vpcId);
        _accountMgr.checkAccess(account, null, true, vpc);
        _accountMgr.checkAccess(account, _vpcOfferingDao.findById(vpcOfferingId), _dcDao.findById(vpc.getZoneId()));

        if (vpc.getVpcOfferingId() == vpcOfferingId) {
            return vpc;
        }

        List<NetworkVO> tiersInVpc = _networksDao.listByVpc(vpcId);
        vpcTiersCanBeMigrated(tiersInVpc, account, networkToOffering, resume);

        if (relatedVpc == null) {
            final long vpcIdFinal = vpcId;
            vpcCopyId = Transaction.execute((TransactionCallback<Long>)(status) -> _networkMigrationManager.makeCopyOfVpc(vpcIdFinal, vpcOfferingId));
        }

        Vpc copyOfVpc = _vpcDao.findById(vpcCopyId);
        _networkMigrationManager.startVpc(copyOfVpc);

        for (Network tier : tiersInVpc) {
            String networkOfferingUuid = networkToOffering.get(tier.getUuid());
            Long networkId = null;
            if (resume && networkOfferingUuid == null) {
                tier = _networksDao.findById(tier.getRelated());
                networkOfferingUuid = networkToOffering.get(tier.getUuid());
                networkId = tier.getId();
            }
            NetworkOfferingVO newNtwkOff = _networkOfferingDao.findByUuid(networkOfferingUuid);

            Account networkAccount = _accountService.getActiveAccountById(tier.getAccountId());
            try {
                _vpcMgr.validateNtwkOffForNtwkInVpc(networkId, newNtwkOff.getId(), tier.getCidr(), tier.getNetworkDomain(), copyOfVpc, tier.getGateway(), networkAccount, tier.getNetworkACLId());
            } catch (InvalidParameterValueException e) {
                logger.error("Specified network offering can not be used in combination with specified vpc offering. Aborting migration. You can re-run with resume = true and the correct uuid.");
                throw e;
            }

            long newPhysicalNetworkId = findPhysicalNetworkId(tier.getDataCenterId(), newNtwkOff.getTags(), newNtwkOff.getTrafficType());

            final long oldNetworkOfferingId = tier.getNetworkOfferingId();
            NetworkOffering oldNtwkOff = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);

            if (networkNeedsMigration(tier, newPhysicalNetworkId, oldNtwkOff, newNtwkOff) || (resume && tier.getRelated() != tier.getId())) {
                migrateNetworkToPhysicalNetwork(tier, oldNtwkOff, newNtwkOff, vpcId, vpcCopyId, newPhysicalNetworkId, account, callerUser);
            }
        }
        _networkMigrationManager.deleteCopyOfVpc(vpcId, vpcCopyId);
        return _vpcDao.findById(vpcCopyId);
    }

    private Network migrateNetworkToPhysicalNetwork(Network network, NetworkOffering oldNtwkOff, NetworkOffering newNtwkOff, Long oldVpcId, Long newVpcId,
            long newPhysicalNetworkId, Account callerAccount, User callerUser) {
        boolean resume = network.getRelated() != network.getId();

        NetworkCopy networkCopy;

        if (resume) {
            Network networkInNewPhysicalNet = network;
            networkCopy = new NetworkCopy(network.getRelated(), networkInNewPhysicalNet);

            if (networkInNewPhysicalNet.getNetworkOfferingId() != newNtwkOff.getId()) {
                throw new InvalidParameterValueException("Failed to resume migrating network as network offering does not match previously specified network offering (" + newNtwkOff.getUuid() + ")");
            }
        } else {
            networkCopy = Transaction.execute((TransactionCallback<NetworkCopy>)(status) -> migrateNetworkInDb(network, oldNtwkOff, newNtwkOff, oldVpcId, newVpcId, newPhysicalNetworkId));
        }

        Long networkIdInOldPhysicalNet = networkCopy.getNetworkIdInOldPhysicalNet();
        Network networkInNewPhysicalNet = networkCopy.getNetworkInNewPhysicalNet();

        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        DataCenter zone = _dcDao.findById(network.getDataCenterId());
        NetworkVO networkInOldPhysNet = _networksDao.findById(networkIdInOldPhysicalNet);

        boolean shouldImplement = (newNtwkOff.isPersistent() || networkInOldPhysNet.getState() == Network.State.Implemented) && networkInNewPhysicalNet.getState() != Network.State.Implemented;

        if (shouldImplement) {
            DeployDestination dest = new DeployDestination(zone, null, null, null);
            logger.debug("Implementing the network " + network + " elements and resources as a part of network update");
            try {
                networkInNewPhysicalNet = _networkMgr.implementNetwork(networkInNewPhysicalNet.getId(), dest, context).second();
            } catch (Exception ex) {
                logger.warn("Failed to implement network " + network + " elements and resources as a part of network update due to ", ex);
                CloudRuntimeException e = new CloudRuntimeException("Failed to implement network (with specified id) elements and resources as a part of network update");
                e.addProxyObject(network.getUuid(), "networkId");
                throw e;
            }
        }

        _networkMigrationManager.assignNicsToNewPhysicalNetwork(networkInOldPhysNet, networkInNewPhysicalNet);
        _networkMigrationManager.deleteCopyOfNetwork(networkIdInOldPhysicalNet, networkInNewPhysicalNet.getId());

        return getNetwork(network.getId());
    }

    private NetworkCopy migrateNetworkInDb(Network network, NetworkOffering oldNtwkOff, NetworkOffering newNtwkOff, Long oldVpcId, Long newVpcId, long newPhysicalNetworkId) {
        Long networkIdInOldPhysicalNet = _networkMigrationManager.makeCopyOfNetwork(network, oldNtwkOff, oldVpcId);
        Network networkInNewPhysicalNet = _networkMigrationManager.upgradeNetworkToNewNetworkOffering(network.getId(), newPhysicalNetworkId, newNtwkOff.getId(), newVpcId);
        return new NetworkCopy(networkIdInOldPhysicalNet, networkInNewPhysicalNet);
    }

    private void vpcTiersCanBeMigrated(List<? extends Network> tiersInVpc, Account account, Map<String, String> networkToOffering, boolean resume) {
        for (Network network : tiersInVpc) {
            String networkOfferingUuid = networkToOffering.get(network.getUuid());

            if (resume && networkOfferingUuid == null) {
                NetworkVO oldVPCtier = _networksDao.findById(network.getRelated());
                networkOfferingUuid = networkToOffering.get(oldVPCtier.getUuid());
            }

            if (networkOfferingUuid == null) {
                throwInvalidIdException("Failed to migrate VPC as the specified tierNetworkOfferings is not complete", String.valueOf(network.getUuid()), "networkUuid");
            }

            NetworkOfferingVO newNtwkOff = _networkOfferingDao.findByUuid(networkOfferingUuid);

            if (newNtwkOff == null) {
                throwInvalidIdException("Failed to migrate VPC as at least one network offering in tierNetworkOfferings does not exist", networkOfferingUuid, "networkOfferingUuid");
            }

            if (!_configMgr.isOfferingForVpc(newNtwkOff)) {
                throw new InvalidParameterValueException(
                        "Network offering " + newNtwkOff.getName() + " (" + newNtwkOff.getUuid() + ") can't be used for VPC networks for network " + network.getName() + "(" + network.getUuid() + ")");
            }

            verifyNetworkCanBeMigrated(account, network);
            long newPhysicalNetworkId = findPhysicalNetworkId(network.getDataCenterId(), newNtwkOff.getTags(), newNtwkOff.getTrafficType());

            final long oldNetworkOfferingId = network.getNetworkOfferingId();
            NetworkOffering oldNtwkOff = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);
            networkNeedsMigration(network, newPhysicalNetworkId, oldNtwkOff, newNtwkOff);
        }
    }

    private void verifyAlreadyMigratedTiers(long migratedVpcId, long vpcOfferingId, Map<String, String> networkToOffering) {
        Vpc migratedVpc = _vpcDao.findById(migratedVpcId);
        if (migratedVpc.getVpcOfferingId() != vpcOfferingId) {
            logger.error("The vpc is already partially migrated in a previous run. The provided vpc offering is not the same as the one used during the first migration process.");
            throw new InvalidParameterValueException(String.format("Failed to resume migrating VPC as VPC offering does not match previously specified VPC offering (%s)",
                    _vpcOfferingDao.findById(migratedVpc.getVpcOfferingId())));
        }

        List<NetworkVO> migratedTiers = _networksDao.listByVpc(migratedVpcId);
        for (Network tier : migratedTiers) {
            String tierNetworkOfferingUuid = networkToOffering.get(tier.getUuid());

            if (StringUtils.isBlank(tierNetworkOfferingUuid)) {
                throwInvalidIdException("Failed to resume migrating VPC as the specified tierNetworkOfferings is not complete", String.valueOf(tier.getUuid()), "networkUuid");
            }

            NetworkOfferingVO newNetworkOffering = _networkOfferingDao.findByUuid(tierNetworkOfferingUuid);
            if (newNetworkOffering == null) {
                throw new InvalidParameterValueException("Failed to migrate VPC as at least one tier offering in tierNetworkOfferings does not exist.");
            }

            if (newNetworkOffering.getId() != tier.getNetworkOfferingId()) {
                NetworkOfferingVO tierNetworkOffering = _networkOfferingDao.findById(tier.getNetworkOfferingId());
                throw new InvalidParameterValueException(
                        "Failed to resume migrating VPC as at least one network offering in tierNetworkOfferings does not match previously specified network offering (network uuid=" + tier.getUuid()
                        + " was previously specified with offering uuid=" + tierNetworkOffering.getUuid() + ")");
            }
        }
    }

    private boolean networkNeedsMigration(Network network, long newPhysicalNetworkId, NetworkOffering oldNtwkOff, NetworkOffering newNtwkOff) {

        if (newNtwkOff == null || newNtwkOff.isSystemOnly()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find network offering.");
            if (newNtwkOff != null) {
                ex.addProxyObject(String.valueOf(newNtwkOff.getId()), "networkOfferingId");
            }
            throw ex;
        }

        if (newNtwkOff.getId() != oldNtwkOff.getId() || network.getId() != network.getRelated()) {
            Collection<String> newProviders = _networkMgr.finalizeServicesAndProvidersForNetwork(newNtwkOff, newPhysicalNetworkId).values();
            Collection<String> oldProviders = _networkMgr.finalizeServicesAndProvidersForNetwork(oldNtwkOff, network.getPhysicalNetworkId()).values();

            if (providersConfiguredForExternalNetworking(newProviders) != providersConfiguredForExternalNetworking(oldProviders)) {
                throw new InvalidParameterValueException("Updating network failed since guest CIDR needs to be changed!");
            }

            if (!canMoveToPhysicalNetwork(network, oldNtwkOff.getId(), newNtwkOff.getId())) {
                throw new InvalidParameterValueException("Can't upgrade from network offering " + oldNtwkOff.getUuid() + " to " + newNtwkOff.getUuid() + "; check logs for more information");
            }

            List<VMInstanceVO> vmInstances = _vmDao.listNonRemovedVmsByTypeAndNetwork(network.getId(), null);
            boolean vmStateIsNotTransitioning = vmInstances.stream().anyMatch(vm -> vm.getState() != VirtualMachine.State.Stopped && vm.getState() != VirtualMachine.State.Running);
            if (vmStateIsNotTransitioning) {
                throw new CloudRuntimeException("Failed to migrate network as at least one VM is not in running or stopped state.");
            }
        } else {
            return false;
        }

        if (newNtwkOff.getState() != NetworkOffering.State.Enabled) {
            throw new InvalidParameterValueException("Failed to migrate network as the specified network offering is not enabled.");
        }
        return true;
    }

    private void verifyNetworkCanBeMigrated(Account callerAccount, Network network) {
        NetworkOffering oldOffering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (oldOffering.isSystemOnly()) {
            throw new InvalidParameterValueException("Failed to migrate network as the specified network is a system network.");
        }

        if (network.getTrafficType() != TrafficType.Guest) {
            throw new InvalidParameterValueException("Can't allow networks which traffic type is not " + TrafficType.Guest);
        }

        _accountMgr.checkAccess(callerAccount, null, true, network);

        boolean validateNetworkReadyToMigrate = (network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup || network.getState() == Network.State.Allocated);
        if (!validateNetworkReadyToMigrate) {
            logger.error("Failed to migrate network as it is in invalid state.");
            CloudRuntimeException ex = new CloudRuntimeException("Failed to migrate network as it is in invalid state.");
            ex.addProxyObject(network.getUuid(), "networkId");
            throw ex;
        }
    }

    @Override
    public boolean canMoveToPhysicalNetwork(Network network, long oldNetworkOfferingId, long newNetworkOfferingId) {
        NetworkOffering oldNetworkOffering = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);
        NetworkOffering newNetworkOffering = _networkOfferingDao.findById(newNetworkOfferingId);

        if (oldNetworkOffering.getGuestType() != GuestType.Isolated) {
            throw new InvalidParameterValueException("NetworkOfferingId can be upgraded only for the network of type " + GuestType.Isolated);
        }

        if (oldNetworkOffering.getGuestType() != newNetworkOffering.getGuestType()) {
            logger.debug("Network offerings {} and {} are of different types, can't upgrade", newNetworkOffering, oldNetworkOffering);
            return false;
        }

        if (oldNetworkOffering.getTrafficType() != newNetworkOffering.getTrafficType()) {
            logger.debug("Network offerings {} and {} have different traffic types, can't upgrade", newNetworkOffering, oldNetworkOffering);
            return false;
        }

        if (oldNetworkOffering.isSpecifyIpRanges() != newNetworkOffering.isSpecifyIpRanges()) {
            logger.debug("Network offerings {} and {} have different values for specifyIpRangess, can't upgrade", newNetworkOffering, oldNetworkOffering);
            return false;
        }

        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), null);
        List<PublicIp> publicIps = new ArrayList<>();
        if (userIps != null && !userIps.isEmpty()) {
            for (IPAddressVO userIp : userIps) {
                PublicIp publicIp = PublicIp.createFromAddrAndVlan(userIp, _vlanDao.findById(userIp.getVlanId()));
                publicIps.add(publicIp);
            }
        }
        if (oldNetworkOffering.isConserveMode() && !newNetworkOffering.isConserveMode()) {
            if (!canIpsUsedForNonConserve(publicIps)) {
                return false;
            }
        }

        if (areServicesSupportedByNetworkOffering(oldNetworkOfferingId, Service.Lb) && areServicesSupportedByNetworkOffering(newNetworkOfferingId, Service.Lb)) {
            if (oldNetworkOffering.isPublicLb() != newNetworkOffering.isPublicLb() || oldNetworkOffering.isInternalLb() != newNetworkOffering.isInternalLb()) {
                throw new InvalidParameterValueException("Original and new offerings support different types of LB - Internal vs Public," + " can't upgrade");
            }
        }

        return canIpsUseOffering(publicIps, newNetworkOfferingId);
    }

    private void throwInvalidIdException(String message, String uuid, String description) {
        InvalidParameterValueException ex = new InvalidParameterValueException(message);
        ex.addProxyObject(uuid, description);
        throw ex;
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

    protected boolean areServicesSupportedByNetworkOffering(long networkOfferingId, Service... services) {
        return _ntwkOfferingSrvcDao.areServicesSupportedByNetworkOffering(networkOfferingId, services);
    }

    protected boolean canIpUsedForNonConserveService(PublicIp ip, Service service) {
        List<PublicIp> ipList = new ArrayList<>();
        ipList.add(ip);
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(ipList, false, false);
        Set<Service> services = ipToServices.get(ip);
        if (services == null || services.isEmpty()) {
            return true;
        }
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
        if (offering.isInline()) {
            Provider firewallProvider = null;
            if (serviceToProviders.containsKey(Service.Firewall)) {
                firewallProvider = (Provider)serviceToProviders.get(Service.Firewall).toArray()[0];
            }
            Set<Provider> p = new HashSet<>();
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
                if (!provider.equals(curProvider)) {
                    throw new InvalidParameterValueException("There would be multiple providers for IP " + ip.getAddress() + " with the new network offering!");
                }
            }
        }
        return true;
    }

    protected Map<PublicIp, Set<Service>> getIpToServices(List<PublicIp> publicIps, boolean rulesRevoked, boolean includingFirewall) {
        Map<PublicIp, Set<Service>> ipToServices = new java.util.HashMap<>();

        if (publicIps != null && !publicIps.isEmpty()) {
            Set<Long> networkSNAT = new HashSet<>();
            for (PublicIp ip : publicIps) {
                Set<Service> services = ipToServices.get(ip);
                if (services == null) {
                    services = new HashSet<>();
                }
                if (ip.isSourceNat()) {
                    if (!networkSNAT.contains(ip.getAssociatedWithNetworkId())) {
                        services.add(Service.SourceNat);
                        networkSNAT.add(ip.getAssociatedWithNetworkId());
                    } else {
                        CloudRuntimeException ex = new CloudRuntimeException("Multiple generic source NAT IPs provided for network");
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

                if (ip.getState() == State.Allocating) {
                    continue;
                }

                Set<Purpose> purposes = getPublicIpPurposeInRules(ip, false, includingFirewall);
                if (ip.isOneToOneNat() && ip.getAssociatedWithVmId() != null) {
                    if (purposes == null) {
                        purposes = new HashSet<>();
                    }
                    purposes.add(Purpose.StaticNat);
                }
                if (purposes == null || purposes.isEmpty()) {
                    purposes = getPublicIpPurposeInRules(ip, true, includingFirewall);
                    if (ip.isOneToOneNat()) {
                        if (purposes == null) {
                            purposes = new HashSet<>();
                        }
                        purposes.add(Purpose.StaticNat);
                    }
                    if (purposes == null || purposes.isEmpty()) {
                        continue;
                    } else {
                        if (rulesRevoked) {
                            ip.setState(State.Releasing);
                        } else {
                            if (ip.getState() == State.Releasing) {
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

    private Set<Purpose> getPublicIpPurposeInRules(PublicIp ip, boolean includeRevoked, boolean includingFirewall) {
        Set<Purpose> result = new HashSet<>();
        List<FirewallRuleVO> rules;
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

    private Network getNetwork(long id) {
        return _networksDao.findById(id);
    }

    private long findPhysicalNetworkId(long zoneId, String tag, TrafficType trafficType) {
        return _networkModel.findPhysicalNetworkId(zoneId, tag, trafficType);
    }

    private static class NetworkCopy {
        private final Long networkIdInOldPhysicalNet;
        private final Network networkInNewPhysicalNet;

        NetworkCopy(Long networkIdInOldPhysicalNet, Network networkInNewPhysicalNet) {
            this.networkIdInOldPhysicalNet = networkIdInOldPhysicalNet;
            this.networkInNewPhysicalNet = networkInNewPhysicalNet;
        }

        Long getNetworkIdInOldPhysicalNet() {
            return networkIdInOldPhysicalNet;
        }

        Network getNetworkInNewPhysicalNet() {
            return networkInNewPhysicalNet;
        }
    }
}
