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

import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.element.NetrisProviderVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * VPC public-IP allocation and release — extracted from
 * {@link VpcManagerImpl}.
 *
 * @see VpcIpAllocationService
 */
@Component
public class VpcIpAllocationServiceImpl implements VpcIpAllocationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VpcDao vpcDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private FirewallRulesDao firewallDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private IpAddressManager ipAddrMgr;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private NsxProviderDao nsxProviderDao;
    @Inject
    private NetrisProviderDao netrisProviderDao;
    @Inject
    private VpcManager vpcManager;

    /**
     * Pre-built search builder for IPs scoped by account / DC / VPC /
     * network with a join to the {@link com.cloud.dc.Vlan.VlanType#VirtualNetwork}
     * VLAN table. Built lazily on first use because the underlying DAOs
     * are only available after Spring wires us up.
     */
    private SearchBuilder<IPAddressVO> ipAddressSearch;

    private synchronized SearchBuilder<IPAddressVO> getIpAddressSearch() {
        if (ipAddressSearch == null) {
            ipAddressSearch = ipAddressDao.createSearchBuilder();
            ipAddressSearch.and("accountId", ipAddressSearch.entity().getAllocatedToAccountId(), Op.EQ);
            ipAddressSearch.and("dataCenterId", ipAddressSearch.entity().getDataCenterId(), Op.EQ);
            ipAddressSearch.and("vpcId", ipAddressSearch.entity().getVpcId(), Op.EQ);
            ipAddressSearch.and("associatedWithNetworkId", ipAddressSearch.entity().getAssociatedWithNetworkId(), Op.EQ);
            final SearchBuilder<VlanVO> virtualNetworkVlanSB = vlanDao.createSearchBuilder();
            virtualNetworkVlanSB.and("vlanType", virtualNetworkVlanSB.entity().getVlanType(), Op.EQ);
            ipAddressSearch
                    .join("virtualNetworkVlanSB", virtualNetworkVlanSB, ipAddressSearch.entity().getVlanId(), virtualNetworkVlanSB.entity().getId(), JoinBuilder.JoinType.INNER);
            ipAddressSearch.done();
        }
        return ipAddressSearch;
    }

    @Override
    public IpAddress associateIPToVpc(final long ipId, final long vpcId) throws ResourceAllocationException, ResourceUnavailableException, InsufficientAddressCapacityException,
            ConcurrentOperationException {
        final Account caller = CallContext.current().getCallingAccount();
        Account owner = null;

        final IpAddress ipToAssoc = networkModel.getIp(ipId);
        if (ipToAssoc != null) {
            accountMgr.checkAccess(caller, null, true, ipToAssoc);
            owner = accountMgr.getAccount(ipToAssoc.getAllocatedToAccountId());
        } else {
            logger.debug("Unable to find ip address by id: " + ipId);
            return null;
        }

        final Vpc vpc = vpcDao.findById(vpcId);
        if (vpc == null) {
            throw new InvalidParameterValueException("Invalid VPC id provided");
        }

        // check permissions
        accountMgr.checkAccess(caller, null, false, owner, vpc);

        logger.debug(String.format("Associating IP [%s] to VPC [%s]", ipToAssoc, vpc));

        final boolean isSourceNatFinal = vpcManager.isSrcNatIpRequired(vpc.getVpcOfferingId())
                && getExistingSourceNatInVpc(vpc.getAccountId(), vpcId, false, false) == null;
        try {
            IPAddressVO updatedIpAddress = Transaction.execute((TransactionCallbackWithException<IPAddressVO, CloudRuntimeException>) status -> {
                final IPAddressVO ip = ipAddressDao.findById(ipId);
                ip.setVpcId(vpcId);
                ip.setSourceNat(isSourceNatFinal);
                ipAddressDao.update(ipId, ip);
                ipAddrMgr.markPublicIpAsAllocated(ip);
                return ipAddressDao.findById(ipId);
            });

            logger.debug(String.format("Successfully assigned IP [%s] to VPC [%s]", ipToAssoc, vpc));
            CallContext.current().putContextParameter(IpAddress.class, ipToAssoc.getUuid());
            return updatedIpAddress;
        } catch (Exception e) {
            String errorMessage = String.format("Failed to associate IP address [%s] to VPC [%s]", ipToAssoc, vpc);
            logger.error(errorMessage, e);
            throw new CloudRuntimeException(errorMessage, e);
        }
    }

    @Override
    public void unassignIPFromVpcNetwork(final long ipId, final long networkId) {
        IPAddressVO ip = ipAddressDao.findById(ipId);
        Network network = networkModel.getNetwork(networkId);
        unassignIPFromVpcNetwork(ip, network);
    }

    @Override
    public void unassignIPFromVpcNetwork(final IPAddressVO ip, final Network network) {
        if (isIpAllocatedToVpc(ip)) {
            return;
        }

        if (ip == null || ip.getVpcId() == null) {
            return;
        }

        logger.debug("Releasing VPC ip address {} from vpc network {}", ip, network);

        final long vpcId = ip.getVpcId();
        boolean success = false;
        try {
            // unassign ip from the VPC router
            success = ipAddrMgr.applyIpAssociations(network, true);
        } catch (final ResourceUnavailableException ex) {
            throw new CloudRuntimeException("Failed to apply ip associations for network id=" + network + " as a part of unassigning ip " + ip + " from vpc", ex);
        }

        if (success) {
            ip.setAssociatedWithNetworkId(null);
            ipAddressDao.update(ip.getId(), ip);
            logger.debug("IP address {} is no longer associated with the network inside vpc {}", ip, vpcDao.findById(vpcId));
        } else {
            throw new CloudRuntimeException(String.format("Failed to apply ip associations for network %s as a part of unassigning ip %s from vpc", network, ip));
        }
        logger.debug("Successfully released VPC ip address " + ip + " back to VPC pool ");
    }

    @Override
    public boolean isIpAllocatedToVpc(final IpAddress ip) {
        return ip != null && ip.getVpcId() != null && (ip.isOneToOneNat() || !firewallDao.listByIp(ip.getId()).isEmpty());
    }

    @Override
    public IPAddressVO getExistingSourceNatInVpc(final long ownerId, final long vpcId, final boolean forNsx, final boolean forNetris) {

        final List<IPAddressVO> addrs = listPublicIpsAssignedToVpc(ownerId, true, vpcId);

        IPAddressVO sourceNatIp = null;
        if (addrs.isEmpty()) {
            return null;
        } else {
            // Account already has ip addresses
            for (final IPAddressVO addr : addrs) {
                if (addr.isSourceNat()) {
                    if (!forNsx && !forNetris) {
                        sourceNatIp = addr;
                    } else {
                        if (addr.isForSystemVms()) {
                            sourceNatIp = addr;
                        }
                    }
                    if (Objects.nonNull(sourceNatIp)) {
                        return sourceNatIp;
                    }
                }
            }

            assert sourceNatIp != null : "How do we get a bunch of ip addresses but none of them are source nat? " + "account=" + ownerId + "; vpcId=" + vpcId;
        }

        return sourceNatIp;
    }

    @Override
    public List<IPAddressVO> listPublicIpsAssignedToVpc(final long accountId, final Boolean sourceNat, final long vpcId) {
        final SearchCriteria<IPAddressVO> sc = getIpAddressSearch().create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("vpcId", vpcId);

        if (sourceNat != null) {
            sc.addAnd("sourceNat", SearchCriteria.Op.EQ, sourceNat);
        }
        sc.setJoinParameters("virtualNetworkVlanSB", "vlanType", VlanType.VirtualNetwork);

        return ipAddressDao.search(sc, null);
    }

    @Override
    public PublicIp assignSourceNatIpAddressToVpc(final Account owner, final Vpc vpc, final Long podId) throws InsufficientAddressCapacityException, ConcurrentOperationException {
        final long dcId = vpc.getZoneId();
        NsxProviderVO nsxProvider = nsxProviderDao.findByZoneId(dcId);
        boolean forNsx = nsxProvider != null;
        NetrisProviderVO netrisProvider = netrisProviderDao.findByZoneId(dcId);
        boolean forNetris = netrisProvider != null;

        final IPAddressVO sourceNatIp = getExistingSourceNatInVpc(owner.getId(), vpc.getId(), forNsx, forNetris);

        PublicIp ipToReturn = null;

        if (sourceNatIp != null) {
            ipToReturn = PublicIp.createFromAddrAndVlan(sourceNatIp, vlanDao.findById(sourceNatIp.getVlanId()));
        } else {
            if (forNsx || forNetris) {
                // Assign VR (helper VM) public NIC IP address from the separate provider Public IP range/pool
                // NSX: VR uses Public IP from the system VM range
                // Netris: VR uses Public IP from the non system VM range
                ipToReturn = ipAddrMgr.assignPublicIpAddress(dcId, podId, owner, Vlan.VlanType.VirtualNetwork, null, null, false, forNsx);
            } else {
                ipToReturn = ipAddrMgr.assignDedicateIpAddress(owner, null, vpc.getId(), dcId, true);
            }
        }

        return ipToReturn;
    }
}
