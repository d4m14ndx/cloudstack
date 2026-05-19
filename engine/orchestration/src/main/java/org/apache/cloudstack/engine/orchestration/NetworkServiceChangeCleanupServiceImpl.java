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

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkServiceMapVO;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.RemoteAccessVpnVO;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.vpn.RemoteAccessVpnService;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;

@Component
public class NetworkServiceChangeCleanupServiceImpl implements NetworkServiceChangeCleanupService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    @Inject
    protected NetworkServiceMapDao networkServiceMapDao;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected RulesManager rulesManager;
    @Inject
    protected LoadBalancingRulesManager lbManager;
    @Inject
    protected FirewallManager firewallManager;
    @Inject
    protected RemoteAccessVpnDao remoteAccessVpnDao;
    @Inject
    protected RemoteAccessVpnService vpnManager;

    @Override
    public List<String> getServicesNotSupportedInNewOffering(final Network network, final long newNetworkOfferingId) {
        final NetworkOffering offering = networkOfferingDao.findById(newNetworkOfferingId);
        final List<String> services = networkOfferingServiceMapDao.listServicesForNetworkOffering(offering.getId());
        final List<NetworkServiceMapVO> serviceMap = networkServiceMapDao.getServicesInNetwork(network.getId());
        final List<String> servicesNotInNewOffering = new ArrayList<>();
        for (final NetworkServiceMapVO serviceVO : serviceMap) {
            boolean inlist = false;
            for (final String service : services) {
                if (serviceVO.getService().equalsIgnoreCase(service)) {
                    inlist = true;
                    break;
                }
            }
            if (!inlist) {
                //ignore Gateway service as this has no effect on the
                //behaviour of network.
                if (!serviceVO.getService().equalsIgnoreCase(Service.Gateway.getName()))
                    servicesNotInNewOffering.add(serviceVO.getService());
            }
        }
        return servicesNotInNewOffering;
    }

    @Override
    public void cleanupConfigForServicesInNetwork(final List<String> services, final Network network) {
        final long networkId = network.getId();
        final Account caller = accountDao.findById(Account.ACCOUNT_ID_SYSTEM);
        final long userId = User.UID_SYSTEM;
        //remove all PF/Static Nat rules for the network
        logger.info("Services: {} are no longer supported in network: {} after applying new network offering: {} removing the related configuration",
                services::toString, network::toString, () -> networkOfferingDao.findById(network.getNetworkOfferingId()));
        if (services.contains(Service.StaticNat.getName()) || services.contains(Service.PortForwarding.getName())) {
            try {
                if (rulesManager.revokeAllPFStaticNatRulesForNetwork(networkId, userId, caller)) {
                    logger.debug("Successfully cleaned up portForwarding/staticNat rules for network {}", network);
                } else {
                    logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup", network);
                }
                if (services.contains(Service.StaticNat.getName())) {
                    //removing static nat configured on ips.
                    //optimizing the db operations using transaction.
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(final TransactionStatus status) {
                            final List<IPAddressVO> ips = ipAddressDao.listStaticNatPublicIps(network.getId());
                            for (final IPAddressVO ip : ips) {
                                ip.setOneToOneNat(false);
                                ip.setAssociatedWithVmId(null);
                                ip.setVmIp(null);
                                ip.setForRouter(false);
                                ipAddressDao.update(ip.getId(), ip);
                            }
                        }
                    });
                }
            } catch (final ResourceUnavailableException ex) {
                logger.warn("Failed to release portForwarding/StaticNat rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
            }
        }
        if (services.contains(Service.SourceNat.getName())) {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(final TransactionStatus status) {
                    final List<IPAddressVO> ips = ipAddressDao.listByAssociatedNetwork(network.getId(), true);
                    //removing static nat configured on ips.
                    for (final IPAddressVO ip : ips) {
                        ip.setSourceNat(false);
                        ipAddressDao.update(ip.getId(), ip);
                    }
                }
            });
        }
        if (services.contains(Service.Lb.getName())) {
            //remove all LB rules for the network
            if (lbManager.removeAllLoadBalanacersForNetwork(networkId, caller, userId)) {
                logger.debug("Successfully cleaned up load balancing rules for network {}", network);
            } else {
                logger.warn("Failed to cleanup LB rules as a part of network {} cleanup", network);
            }
        }

        if (services.contains(Service.Firewall.getName())) {
            //revoke all firewall rules for the network
            try {
                if (firewallManager.revokeAllFirewallRulesForNetwork(network, userId, caller)) {
                    logger.debug("Successfully cleaned up firewallRules rules for network {}", network);
                } else {
                    logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup", network);
                }
            } catch (final ResourceUnavailableException ex) {
                logger.warn("Failed to cleanup Firewall rules as a part of network {} cleanup due to resourceUnavailable", network, ex);
            }
        }

        //do not remove vpn service for vpc networks.
        if (services.contains(Service.Vpn.getName()) && network.getVpcId() == null) {
            final RemoteAccessVpnVO vpn = remoteAccessVpnDao.findByAccountAndNetwork(network.getAccountId(), networkId);
            try {
                vpnManager.destroyRemoteAccessVpnForIp(vpn.getServerAddressId(), caller, true);
            } catch (final ResourceUnavailableException ex) {
                logger.warn("Failed to cleanup remote access vpn resources of network: {} due to Exception: {}", network, ex);
            }
        }
    }
}
