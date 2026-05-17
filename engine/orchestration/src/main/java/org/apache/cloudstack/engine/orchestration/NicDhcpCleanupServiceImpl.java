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

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import com.cloud.utils.db.DB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.Nic;
import com.cloud.vm.NicIpAlias;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicIpAliasVO;

/**
 * DHCP/DNS entry cleanup for NICs during the NIC removal lifecycle --
 * extracted from {@link NetworkOrchestrator}.
 *
 * @see NicDhcpCleanupService
 */
@Component
public class NicDhcpCleanupServiceImpl implements NicDhcpCleanupService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NetworkServiceMapDao networkServiceMapDao;

    @Inject
    protected NetworkModel networkModel;

    @Inject
    protected NetworkDao networksDao;

    @Inject
    protected NicDao nicDao;

    @Inject
    protected NicIpAliasDao nicIpAliasDao;

    @Inject
    protected com.cloud.network.dao.IPAddressDao publicIpAddressDao;

    @Inject
    protected NetworkProviderResolutionService networkProviderResolutionService;

    /** Injected by the orchestrator after construction. */
    protected List<NetworkElement> networkElements;

    @Override
    public void cleanupNicDhcpDnsEntry(Network network, VirtualMachineProfile vmProfile, NicProfile nicProfile) {
        final List<String> providerNames = networkServiceMapDao.getDistinctProviders(network.getId());
        final List<Network.Provider> networkProviders = new java.util.ArrayList<>();
        for (final String providerName : providerNames) {
            networkProviders.add(Network.Provider.getProvider(providerName));
        }

        for (final NetworkElement element : networkElements) {
            if (networkProviders.contains(element.getProvider())) {
                if (!networkModel.isProviderEnabledInPhysicalNetwork(networkModel.getPhysicalNetworkId(network), element.getProvider().getName())) {
                    throw new CloudRuntimeException("Service provider " + element.getProvider().getName() + " either doesn't exist or is not enabled in physical network id: "
                            + network.getPhysicalNetworkId());
                }
                if (vmProfile.getType() == VirtualMachine.Type.User && element.getProvider() != null) {
                    if (networkModel.areServicesSupportedInNetwork(network.getId(), Network.Service.Dhcp)
                            && networkModel.isProviderSupportServiceInNetwork(network.getId(), Network.Service.Dhcp, element.getProvider()) && element instanceof DhcpServiceProvider) {
                        final DhcpServiceProvider sp = (DhcpServiceProvider) element;
                        try {
                            sp.removeDhcpEntry(network, nicProfile, vmProfile);
                        } catch (ResourceUnavailableException e) {
                            logger.error("Failed to remove dhcp-dns entry due to: ", e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean isDhcpAccrossMultipleSubnetsSupported(final DhcpServiceProvider dhcpServiceProvider) {
        final Map<Network.Capability, String> capabilities = dhcpServiceProvider.getCapabilities().get(Network.Service.Dhcp);
        final String supportsMultipleSubnets = capabilities.get(Network.Capability.DhcpAccrossMultipleSubnets);
        if (supportsMultipleSubnets != null && Boolean.valueOf(supportsMultipleSubnets)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean isLastNicInSubnet(final NicVO nic) {
        if (nicDao.listByNetworkIdTypeAndGatewayAndBroadcastUri(nic.getNetworkId(), VirtualMachine.Type.User, nic.getIPv4Gateway(), nic.getBroadcastUri()).size() > 1) {
            return false;
        }
        return true;
    }

    @DB
    @Override
    public void removeDhcpServiceInSubnet(final Nic nic) {
        final Network network = networksDao.findById(nic.getNetworkId());
        final DhcpServiceProvider dhcpServiceProvider = networkProviderResolutionService.getDhcpServiceProvider(network);
        try {
            final NicIpAliasVO ipAlias = nicIpAliasDao.findByGatewayAndNetworkIdAndState(nic.getIPv4Gateway(), network.getId(), NicIpAlias.State.active);
            if (ipAlias != null) {
                ipAlias.setState(NicIpAlias.State.revoked);
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        nicIpAliasDao.update(ipAlias.getId(), ipAlias);
                        final IPAddressVO aliasIpaddressVo = publicIpAddressDao.findByIpAndSourceNetworkId(ipAlias.getNetworkId(), ipAlias.getIp4Address());
                        publicIpAddressDao.unassignIpAddress(aliasIpaddressVo.getId());
                    }
                });
                if (!dhcpServiceProvider.removeDhcpSupportForSubnet(network)) {
                    logger.warn("Failed to remove the IP alias on the router, marking it as removed in db and freed the allocated IP {}", ipAlias.getIp4Address());
                }
            }
        } catch (final ResourceUnavailableException e) {
            //failed to remove the dhcpconfig on the router.
            logger.info("Unable to delete the IP alias due to unable to contact the virtualrouter.");
        }
    }
}
