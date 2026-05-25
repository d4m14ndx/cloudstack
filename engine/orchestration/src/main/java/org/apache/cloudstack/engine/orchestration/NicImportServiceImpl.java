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

import java.util.Date;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.user.Account;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.Nic.ReservationStrategy;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.dao.NicDao;

/**
 * Handles import-NIC operations — extracted from {@link NetworkOrchestrator}.
 *
 * @see NicImportService
 */
@Component
public class NicImportServiceImpl implements NicImportService {

    protected Logger logger = LogManager.getLogger(NicImportServiceImpl.class);

    @Inject
    protected NicDao nicDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected IpAddressManager ipAddrMgr;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected NicProfileMtuService nicProfileMtuService;

    @DB
    @Override
    public Pair<NicProfile, Integer> importNic(final String macAddress, int deviceId, final Network network, final Boolean isDefaultNic, final VirtualMachine vm,
            final Network.IpAddresses ipAddresses, final DataCenter dataCenter, final boolean forced)
            throws ConcurrentOperationException, InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException {
        logger.debug("Allocating NIC for Instance {} in Network {} during import", vm, network);
        String selectedIp = null;
        if (ipAddresses != null && StringUtils.isNotEmpty(ipAddresses.getIp4Address())) {
            if (ipAddresses.getIp4Address().equals("auto")) {
                ipAddresses.setIp4Address(null);
            }
            selectedIp = getSelectedIpForNicImport(network, dataCenter, ipAddresses);
            if (selectedIp == null && network.getGuestType() != GuestType.L2 && !networkModel.listNetworkOfferingServices(network.getNetworkOfferingId()).isEmpty()) {
                throw new InsufficientVirtualNetworkCapacityException("Unable to acquire Guest IP  address for network " + network, DataCenter.class,
                        network.getDataCenterId());
            }
        }
        final String finalSelectedIp = selectedIp;
        final NicVO vo = Transaction.execute(new TransactionCallback<>() {
            @Override
            public NicVO doInTransaction(TransactionStatus status) {
                if (StringUtils.isBlank(macAddress)) {
                    throw new CloudRuntimeException("Mac address not specified");
                }
                String macAddressToPersist = macAddress.trim();
                if (!NetUtils.isValidMac(macAddressToPersist)) {
                    throw new CloudRuntimeException("Invalid mac address: " + macAddressToPersist);
                }
                NicVO existingNic = nicDao.findByNetworkIdAndMacAddress(network.getId(), macAddressToPersist);
                if (existingNic != null) {
                    macAddressToPersist = generateNewMacAddressIfForced(network, macAddressToPersist, forced);
                }
                NicVO vo = new NicVO(network.getGuruName(), vm.getId(), network.getId(), vm.getType());
                vo.setMacAddress(macAddressToPersist);
                vo.setAddressFormat(Networks.AddressFormat.Ip4);
                Pair<String, String> pair = getNetworkGatewayAndNetmaskForNicImport(network, dataCenter, finalSelectedIp);
                String gateway = pair.first();
                String netmask = pair.second();
                if (NetUtils.isValidIp4(finalSelectedIp) && StringUtils.isNotEmpty(gateway)) {
                    vo.setIPv4Address(finalSelectedIp);
                    vo.setIPv4Gateway(gateway);
                    vo.setIPv4Netmask(netmask);
                }
                vo.setBroadcastUri(network.getBroadcastUri());
                vo.setMode(network.getMode());
                vo.setState(Nic.State.Reserved);
                vo.setReservationStrategy(ReservationStrategy.Start);
                vo.setReservationId(UUID.randomUUID().toString());
                vo.setIsolationUri(network.getBroadcastUri());
                vo.setDeviceId(deviceId);
                vo.setDefaultNic(isDefaultNic);
                vo = nicDao.persist(vo);

                int count = 1;
                if (vo.getVmType() == VirtualMachine.Type.User) {
                    logger.debug("Changing active number of nics for network {} on {}", network, count);
                    networksDao.changeActiveNicsBy(network.getId(), count);
                }
                if (vo.getVmType() == VirtualMachine.Type.User
                        || vo.getVmType() == VirtualMachine.Type.DomainRouter && networksDao.findById(network.getId()).getTrafficType() == com.cloud.network.Networks.TrafficType.Guest) {
                    networksDao.setCheckForGc(network.getId());
                }
                if (vm.getType() == Type.DomainRouter) {
                    Pair<NetworkVO, VpcVO> networks = nicProfileMtuService.getGuestNetworkRouterAndVpcDetails(vm.getId());
                    nicProfileMtuService.setMtuDetailsInVRNic(networks, network, vo);
                }

                return vo;
            }
        });

        if (selectedIp != null && GuestType.Shared.equals(network.getGuestType())) {
            IPAddressVO ipAddressVO = ipAddressDao.findByIpAndSourceNetworkId(network.getId(), selectedIp);
            if (ipAddressVO != null && IpAddress.State.Free.equals(ipAddressVO.getState())) {
                ipAddressVO.setState(IPAddressVO.State.Allocated);
                ipAddressVO.setAllocatedTime(new Date());
                Account account = accountDao.findById(vm.getAccountId());
                ipAddressVO.setAllocatedInDomainId(account.getDomainId());
                ipAddressVO.setAllocatedToAccountId(account.getId());
                ipAddressDao.update(ipAddressVO.getId(), ipAddressVO);
            }
        }

        final Integer networkRate = networkModel.getNetworkRate(network.getId(), vm.getId());
        final NicProfile vmNic = new NicProfile(vo, network, vo.getBroadcastUri(), vo.getIsolationUri(), networkRate, networkModel.isSecurityGroupSupportedInNetwork(network),
                networkModel.getNetworkTag(vm.getHypervisorType(), network));

        return new Pair<>(vmNic, Integer.valueOf(deviceId));
    }

    @Override
    public String getSelectedIpForNicImport(Network network, DataCenter dataCenter, Network.IpAddresses ipAddresses) {
        if (network.getGuestType() == GuestType.L2) {
            return null;
        }
        return GuestType.Shared.equals(network.getGuestType()) ?
                getSelectedIpForNicImportOnSharedNetwork(ipAddresses.getIp4Address(), network, dataCenter) :
                ipAddrMgr.acquireGuestIpAddress(network, ipAddresses.getIp4Address());
    }

    @Override
    public String getSelectedIpForNicImportOnSharedNetwork(String requestedIp, Network network, DataCenter dataCenter) {
        IPAddressVO ipAddressVO = StringUtils.isBlank(requestedIp) ?
                ipAddressDao.findBySourceNetworkIdAndDatacenterIdAndState(network.getId(), dataCenter.getId(), IpAddress.State.Free) :
                ipAddressDao.findByIpAndSourceNetworkId(network.getId(), requestedIp);
        if (ipAddressVO == null || ipAddressVO.getState() != IpAddress.State.Free) {
            String msg = String.format("Cannot find a free IP to assign to VM NIC on network %s", network.getName());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        return ipAddressVO.getAddress() != null ? ipAddressVO.getAddress().addr() : null;
    }

    /**
     * Obtain the gateway and netmask for a VM NIC to import.
     * If the VM to import is on a Basic Zone, then obtain the information from the vlan table instead of the network.
     */
    @Override
    public Pair<String, String> getNetworkGatewayAndNetmaskForNicImport(Network network, DataCenter dataCenter, String selectedIp) {
        String gateway = network.getGateway();
        String netmask = StringUtils.isNotEmpty(network.getCidr()) ? NetUtils.cidr2Netmask(network.getCidr()) : null;
        if (dataCenter.getNetworkType() == NetworkType.Basic) {
            IPAddressVO freeIp = ipAddressDao.findByIp(selectedIp);
            if (freeIp != null) {
                VlanVO vlan = vlanDao.findById(freeIp.getVlanId());
                gateway = vlan != null ? vlan.getVlanGateway() : null;
                netmask = vlan != null ? vlan.getVlanNetmask() : null;
            }
        }
        return new Pair<>(gateway, netmask);
    }

    private String generateNewMacAddressIfForced(Network network, String macAddress, boolean forced) {
        if (!forced) {
            throw new CloudRuntimeException("NIC with MAC address " + macAddress + " exists on network " + network +
                    " and forced flag is disabled");
        }
        try {
            logger.debug("Generating a new mac address on network {} as the mac address {} already exists", network, macAddress);
            String newMacAddress = networkModel.getNextAvailableMacAddressInNetwork(network.getId());
            logger.debug("Successfully generated the mac address {}, using it instead of the conflicting address {}", newMacAddress, macAddress);
            return newMacAddress;
        } catch (InsufficientAddressCapacityException e) {
            String msg = String.format("Could not generate a new mac address on network %s", network);
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
    }
}
