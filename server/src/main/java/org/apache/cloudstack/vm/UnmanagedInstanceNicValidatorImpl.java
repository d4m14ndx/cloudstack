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
package org.apache.cloudstack.vm;

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Pre-flight NIC / network validation for unmanaged-VM import —
 * extracted from {@link UnmanagedVMsManagerImpl}.
 *
 * @see UnmanagedInstanceNicValidator
 */
@Component
public class UnmanagedInstanceNicValidatorImpl implements UnmanagedInstanceNicValidator {
    private static final Logger LOG = LogManager.getLogger(UnmanagedInstanceNicValidatorImpl.class);

    @Inject
    private NetworkModel networkModel;
    @Inject
    private VMInstanceDao vmDao;
    @Inject
    private NicDao nicDao;

    @Override
    public void basicNetworkChecks(String instanceName, UnmanagedInstanceTO.Nic nic, Network network) {
        if (nic == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Unable to retrieve the NIC details used by VM [%s] from VMware. Please check if this VM have NICs in VMWare.", instanceName));
        }
        if (network == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Network for nic ID: %s not found during VM import.", nic.getNicId()));
        }
    }

    @Override
    public void checksOnlyNeededForVmware(UnmanagedInstanceTO.Nic nic, Network network, Hypervisor.HypervisorType hypervisorType) {
        if (hypervisorType == Hypervisor.HypervisorType.VMware) {
            String networkBroadcastUri = network.getBroadcastUri() == null ? null : network.getBroadcastUri().toString();
            if (nic.getVlan() != null && nic.getVlan() != 0 && nic.getPvlan() == null &&
                    (StringUtils.isEmpty(networkBroadcastUri) ||
                            !networkBroadcastUri.equals(String.format("vlan://%d", nic.getVlan())))) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("VLAN of network(ID: %s) %s is found different from the VLAN of nic(ID: %s) vlan://%d during VM import", network.getUuid(), networkBroadcastUri, nic.getNicId(), nic.getVlan()));
            }
            String pvLanType = nic.getPvlanType() == null ? "" : nic.getPvlanType().toLowerCase().substring(0, 1);
            if (nic.getVlan() != null && nic.getVlan() != 0 && nic.getPvlan() != null && nic.getPvlan() != 0 &&
                    (StringUtils.isEmpty(networkBroadcastUri) || !String.format("pvlan://%d-%s%d", nic.getVlan(), pvLanType, nic.getPvlan()).equals(networkBroadcastUri))) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("PVLAN of network(ID: %s) %s is found different from the VLAN of nic(ID: %s) pvlan://%d-%s%d during VM import", network.getUuid(), networkBroadcastUri, nic.getNicId(), nic.getVlan(), pvLanType, nic.getPvlan()));
            }
        }
    }

    @Override
    public void checkUnmanagedNicAndNetworkForImport(String instanceName, UnmanagedInstanceTO.Nic nic, Network network,
                                                    DataCenter zone, Account owner, boolean autoAssign,
                                                    Hypervisor.HypervisorType hypervisorType) throws ServerApiException {
        basicNetworkChecks(instanceName, nic, network);
        if (network.getDataCenterId() != zone.getId()) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Network(ID: %s) for nic(ID: %s) belongs to a different zone than VM to be imported", network.getUuid(), nic.getNicId()));
        }
        networkModel.checkNetworkPermissions(owner, network);
        if (!autoAssign && network.getGuestType().equals(Network.GuestType.Isolated)) {
            return;
        }
        checksOnlyNeededForVmware(nic, network, hypervisorType);
    }

    @Override
    public void checkUnmanagedNicAndNetworkHostnameForImport(String instanceName, UnmanagedInstanceTO.Nic nic, Network network,
                                                            String hostName) throws ServerApiException {
        basicNetworkChecks(instanceName, nic, network);
        // Check for duplicate hostname in network, get all vms hostNames in the network
        List<String> hostNames = vmDao.listDistinctHostNames(network.getId());
        if (CollectionUtils.isNotEmpty(hostNames) && hostNames.contains(hostName)) {
            throw new InvalidParameterValueException(String.format("VM with Name [%s] already exists in the network [%s] domain [%s]. Cannot import another VM with the same name. Please try again with a different name.", hostName, network, network.getNetworkDomain()));
        }
    }

    @Override
    public void checkUnmanagedNicIpAndNetworkForImport(String instanceName, UnmanagedInstanceTO.Nic nic, Network network,
                                                      Network.IpAddresses ipAddresses) throws ServerApiException {
        basicNetworkChecks(instanceName, nic, network);
        // Check IP is assigned for non L2 networks
        if (!network.getGuestType().equals(Network.GuestType.L2) && (ipAddresses == null || StringUtils.isEmpty(ipAddresses.getIp4Address()))) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("NIC(ID: %s) needs a valid IP address for it to be associated with network(ID: %s). %s parameter of API can be used for this", nic.getNicId(), network.getUuid(), ApiConstants.NIC_IP_ADDRESS_LIST));
        }
        // If network is non L2, IP v4 is assigned and not set to auto-assign, check it is available for network
        if (!network.getGuestType().equals(Network.GuestType.L2) && ipAddresses != null && StringUtils.isNotEmpty(ipAddresses.getIp4Address()) && !ipAddresses.getIp4Address().equals("auto")) {
            Set<Long> ips = networkModel.getAvailableIps(network, ipAddresses.getIp4Address());
            if (CollectionUtils.isEmpty(ips) || !ips.contains(NetUtils.ip2Long(ipAddresses.getIp4Address()))) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("IP address %s for NIC(ID: %s) is not available in network(ID: %s)", ipAddresses.getIp4Address(), nic.getNicId(), network.getUuid()));
            }
        }
    }

    @Override
    public void checkUnmanagedNicAndNetworkMacAddressForImport(NetworkVO network, UnmanagedInstanceTO.Nic nic, boolean forced) {
        NicVO existingNic = nicDao.findByNetworkIdAndMacAddress(network.getId(), nic.getMacAddress());
        if (existingNic != null && !forced) {
            String err = String.format("NIC %s with MAC address %s already exists on network %s and forced flag is disabled. " +
                    "Retry with forced flag enabled if a new MAC address to be generated.", nic, nic.getMacAddress(), network);
            LOG.error(err);
            throw new CloudRuntimeException(err);
        }
    }
}
