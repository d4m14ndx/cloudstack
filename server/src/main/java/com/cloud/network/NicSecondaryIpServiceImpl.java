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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.security.SecurityGroupService;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.DB;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.vm.Nic;
import com.cloud.vm.NicSecondaryIp;
import com.cloud.vm.NicVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.dao.UserVmDao;

/**
 * Default implementation of {@link NicSecondaryIpService}. Extracted from
 * {@code NetworkServiceImpl} as part of the Phase 4 god-class decomposition
 * effort (slice 5).
 */
@Component
public class NicSecondaryIpServiceImpl implements NicSecondaryIpService {

    private static final Logger logger = LogManager.getLogger(NicSecondaryIpServiceImpl.class);

    /**
     * Component name used as the sender identifier when publishing message bus
     * events. Mirrors the {@code _name} value that {@code NetworkServiceImpl}
     * inherits from {@code ManagerBase} so external listeners see the same
     * sender regardless of which class published the event.
     */
    protected static final String COMPONENT_NAME = "NetworkService";

    @Inject
    protected NicDao nicDao;
    @Inject
    protected NicSecondaryIpDao nicSecondaryIpDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected FirewallRulesDao firewallDao;
    @Inject
    protected PortForwardingRulesDao portForwardingDao;
    @Inject
    protected LoadBalancerDao loadBalancerDao;
    @Inject
    protected ConfigurationDao configDao;
    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected IpAddressManager ipAddrMgr;
    @Inject
    protected Ipv6AddressManager ipv6AddrMgr;
    @Inject
    protected SecurityGroupService securityGroupService;
    @Inject
    protected MessageBus messageBus;

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NIC_SECONDARY_IP_CONFIGURE, eventDescription = "Configuring secondary IP rules", async = true)
    public boolean configureNicSecondaryIp(NicSecondaryIp secIp, boolean isZoneSgEnabled) {
        boolean success;
        String secondaryIp = secIp.getIp4Address();
        if (secIp.getIp4Address() == null) {
            secondaryIp = secIp.getIp6Address();
        }

        if (isZoneSgEnabled) {
            success = securityGroupService.securityGroupRulesForVmSecIp(secIp.getNicId(), secondaryIp, true);
            logger.info("Associated IP address to NIC : {}", secIp.getIp4Address());
        } else {
            success = true;
        }
        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NIC_SECONDARY_IP_ASSIGN, eventDescription = "Assigning secondary IP to NIC", create = true)
    public NicSecondaryIp allocateSecondaryGuestIP(final long nicId, IpAddresses requestedIpPair) throws InsufficientAddressCapacityException {

        Account caller = CallContext.current().getCallingAccount();
        String ipv4Address = requestedIpPair.getIp4Address();
        String ipv6Address = requestedIpPair.getIp6Address();

        //check whether the nic belongs to user vm.
        NicVO nicVO = nicDao.findById(nicId);
        if (nicVO == null) {
            throw new InvalidParameterValueException("There is no NIC with the ID:  " + nicId);
        }

        if (nicVO.getVmType() != VirtualMachine.Type.User) {
            throw new InvalidParameterValueException(String.format("The NIC [%s] does not belong to a user Instance", nicVO.getUuid()));
        }

        VirtualMachine vm = userVmDao.findById(nicVO.getInstanceId());
        if (vm == null) {
            throw new InvalidParameterValueException(String.format("There is no Instance with the NIC [%s]", nicVO.getUuid()));
        }

        final long networkId = nicVO.getNetworkId();
        final Account ipOwner = accountMgr.getAccount(vm.getAccountId());

        // verify permissions
        accountMgr.checkAccess(caller, null, true, vm);

        Network network = networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Invalid Network id is given");
        }

        int maxAllowedIpsPerNic = NumbersUtil.parseInt(configDao.getValue(Config.MaxNumberOfSecondaryIPsPerNIC.key()), Integer.parseInt(Config.MaxNumberOfSecondaryIPsPerNIC.getDefaultValue()));
        Long nicWiseIpCount = nicSecondaryIpDao.countByNicId(nicId);
        if (nicWiseIpCount.intValue() >= maxAllowedIpsPerNic) {
            logger.error("Maximum Number of IPs \"vm.network.nic.max.secondary.ipaddresses = \"{} per NIC has been crossed for the NIC {}.", maxAllowedIpsPerNic, nicVO);
            throw new InsufficientAddressCapacityException("Maximum Number of IPs per NIC has been crossed.", Nic.class, nicId);
        }

        logger.debug("Calling the IP allocation ...");
        String ipaddr = null;
        String ip6addr = null;
        //Isolated network can exist in Basic zone only, so no need to verify the zone type
        if (network.getGuestType() == Network.GuestType.Isolated) {
            if ((ipv4Address != null || NetUtils.isIpv4(network.getGateway()) && StringUtils.isBlank(ipv6Address))) {
                ipaddr = ipAddrMgr.allocateGuestIP(network, ipv4Address);
            }
            if (StringUtils.isNotBlank(ipv6Address)) {
                ip6addr = ipv6AddrMgr.allocateGuestIpv6(network, ipv6Address);
            }
        } else if (network.getGuestType() == Network.GuestType.Shared) {
            //for basic zone, need to provide the podId to ensure proper IP allocation
            Long podId = null;
            DataCenter dc = dcDao.findById(network.getDataCenterId());

            if (dc.getNetworkType() == NetworkType.Basic) {
                VMInstanceVO vmi = (VMInstanceVO) vm;
                podId = vmi.getPodIdToDeployIn();
                if (podId == null) {
                    throw new InvalidParameterValueException("Instance Pod id is null in Basic zone; can't decide the range for IP allocation");
                }
            }

            try {
                if (ipv6Address != null) {
                    ip6addr = ipv6AddrMgr.allocatePublicIp6ForGuestNic(network, podId, ipOwner, ipv6Address);
                } else {
                    ipaddr = ipAddrMgr.allocatePublicIpForGuestNic(network, podId, ipOwner, ipv4Address);
                }
                if (ipaddr == null && ipv6Address == null) {
                    throw new InvalidParameterValueException(String.format("Allocating IP to guest NIC %s failed", nicVO));
                }
            } catch (InsufficientAddressCapacityException e) {
                logger.error("Allocating IP to guest NIC {} failed", nicVO);
                return null;
            }
        } else {
            logger.error("AddIpToVMNic is not supported in this network...");
            return null;
        }

        if (!StringUtils.isAllBlank(ipaddr, ip6addr)) {
            // we got the IP addr so up the nics table and secondary IP
            final String ip4AddrFinal = ipaddr;
            final String ip6AddrFinal = ip6addr;
            long id = Transaction.execute(new TransactionCallback<Long>() {
                @Override
                public Long doInTransaction(TransactionStatus status) {
                    boolean nicSecondaryIpSet = nicVO.getSecondaryIp();
                    if (!nicSecondaryIpSet) {
                        nicVO.setSecondaryIp(true);
                        // commit when previously set ??
                        logger.debug("Setting nics table ...");
                        nicDao.update(nicId, nicVO);
                    }

                    logger.debug("Setting nic_secondary_ip table ...");
                    Long vmId = nicVO.getInstanceId();
                    NicSecondaryIpVO secondaryIpVO = new NicSecondaryIpVO(nicId, ip4AddrFinal, ip6AddrFinal, vmId, ipOwner.getId(), ipOwner.getDomainId(), networkId);
                    nicSecondaryIpDao.persist(secondaryIpVO);
                    return secondaryIpVO.getId();
                }
            });

            messageBus.publish(COMPONENT_NAME, NetworkService.MESSAGE_ASSIGN_NIC_SECONDARY_IP_EVENT, PublishScope.LOCAL, id);

            return getNicSecondaryIp(id);
        } else {
            return null;
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NIC_SECONDARY_IP_UNASSIGN, eventDescription = "Removing secondary IP from NIC", async = true)
    public boolean releaseSecondaryIpFromNic(long ipAddressId) {
        Account caller = CallContext.current().getCallingAccount();

        // Verify input parameters
        NicSecondaryIpVO secIpVO = nicSecondaryIpDao.findById(ipAddressId);
        if (secIpVO == null) {
            throw new InvalidParameterValueException("Unable to find secondary IP address by id");
        }

        VirtualMachine vm = userVmDao.findById(secIpVO.getVmId());
        if (vm == null) {
            throw new InvalidParameterValueException("There is no Instance with the given secondary ip");
        }
        // verify permissions
        accountMgr.checkAccess(caller, null, true, vm);

        Network network = networksDao.findById(secIpVO.getNetworkId());

        if (network == null) {
            throw new InvalidParameterValueException("Invalid network id is given");
        }

        // Validate network offering
        NetworkOfferingVO ntwkOff = networkOfferingDao.findById(network.getNetworkOfferingId());

        Long nicId = secIpVO.getNicId();
        logger.debug("IP = {} NIC = {}", secIpVO, nicDao.findById(nicId));
        //check is this the last secondary ip for NIC
        List<NicSecondaryIpVO> ipList = nicSecondaryIpDao.listByNicId(nicId);
        boolean lastIp = false;
        if (ipList.size() == 1) {
            // this is the last secondary IP to NIC
            lastIp = true;
        }

        DataCenter dc = dcDao.findById(network.getDataCenterId());
        if (dc == null) {
            throw new InvalidParameterValueException("Invalid zone Id is given");
        }

        logger.debug("Calling secondary IP {} release ", secIpVO);
        if (dc.getNetworkType() == NetworkType.Advanced && network.getGuestType() == Network.GuestType.Isolated) {
            //check PF or static NAT is configured on this IP address
            String secondaryIp = secIpVO.getIp4Address();
            List<FirewallRuleVO> fwRulesList = firewallDao.listByNetworkAndPurpose(network.getId(), Purpose.PortForwarding);

            if (fwRulesList.size() != 0) {
                for (FirewallRuleVO rule : fwRulesList) {
                    if (portForwardingDao.findByIdAndIp(rule.getId(), secondaryIp) != null) {
                        logger.debug("Instance NIC IP {} is associated with the port forwarding rule", secondaryIp);
                        throw new InvalidParameterValueException("Can't remove the secondary IP " + secondaryIp + " is associate with the port forwarding rule");
                    }
                }
            }
            //check if the secondary IP associated with any static nat rule
            IPAddressVO publicIpVO = ipAddressDao.findByIpAndNetworkId(secIpVO.getNetworkId(), secondaryIp);
            if (publicIpVO != null) {
                logger.debug("VM NIC IP {} is associated with the static NAT rule public IP address ID: {}", secondaryIp, publicIpVO);
                throw new InvalidParameterValueException(String.format("Can't remove the IP %s is associate with static NAT rule public IP address ID: %s", secondaryIp, publicIpVO));
            }

            if (loadBalancerDao.isLoadBalancerRulesMappedToVmGuestIp(vm.getId(), secondaryIp, network.getId())) {
                logger.debug("VM nic IP {} is mapped to load balancing rule", secondaryIp);
                throw new InvalidParameterValueException("Can't remove the secondary IP " + secondaryIp + " is mapped to load balancing rule");
            }

        } else if (dc.getNetworkType() == NetworkType.Basic || ntwkOff.getGuestType() == Network.GuestType.Shared) {
            final IPAddressVO ip = ipAddressDao.findByIpAndSourceNetworkId(secIpVO.getNetworkId(), secIpVO.getIp4Address());
            if (ip != null) {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        ipAddrMgr.markIpAsUnavailable(ip.getId());
                        ipAddressDao.unassignIpAddress(ip.getId());
                    }
                });
            }
        } else {
            throw new InvalidParameterValueException("Not supported for this network now");
        }

        return removeNicSecondaryIP(secIpVO, lastIp);
    }

    protected boolean removeNicSecondaryIP(final NicSecondaryIpVO ipVO, final boolean lastIp) {
        final long nicId = ipVO.getNicId();
        final NicVO nic = nicDao.findById(nicId);

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                if (lastIp) {
                    nic.setSecondaryIp(false);
                    logger.debug("Setting NICs secondary IP to false ...");
                    nicDao.update(nicId, nic);
                }

                logger.debug("Removing NIC secondary IP entry ...");
                nicSecondaryIpDao.remove(ipVO.getId());
            }
        });

        messageBus.publish(COMPONENT_NAME, NetworkService.MESSAGE_RELEASE_NIC_SECONDARY_IP_EVENT, PublishScope.LOCAL, ipVO);

        return true;
    }

    protected NicSecondaryIp getNicSecondaryIp(long id) {
        return nicSecondaryIpDao.findById(id);
    }
}
