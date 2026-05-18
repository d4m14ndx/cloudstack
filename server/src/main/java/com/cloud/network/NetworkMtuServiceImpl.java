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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.alert.AlertService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

@Component
public class NetworkMtuServiceImpl implements NetworkMtuService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected VpcDao _vpcDao;
    @Inject
    protected AlertManager alertManager;
    @Inject
    protected DomainRouterDao routerDao;
    @Inject
    protected DomainRouterJoinDao routerJoinDao;
    @Inject
    protected IPAddressDao _ipAddressDao;
    @Inject
    protected VlanDao _vlanDao;
    @Inject
    protected NicDao _nicDao;
    @Inject
    protected NetworkDao _networksDao;
    @Inject
    protected CommandSetupHelper commandSetupHelper;
    @Autowired
    @Qualifier("networkHelper")
    protected NetworkHelper networkHelper;

    @Override
    public void mtuCheckForVpcNetwork(Long vpcId, Pair<Integer, Integer> interfaceMTUs, Integer publicMtu) {
        if (vpcId != null && publicMtu != null) {
            VpcVO vpc = _vpcDao.findById(vpcId);
            if (vpc == null) {
                throw new CloudRuntimeException(String.format("VPC with id %s not found", vpcId));
            }
            logger.warn(String.format("VPC public MTU already set at VPC creation phase to: %s. Ignoring public MTU " +
                    "passed during VPC network tier creation ", vpc.getPublicMtu()));
            interfaceMTUs.set(vpc.getPublicMtu(), interfaceMTUs.second());
        }
    }

    @Override
    public Pair<Integer, Integer> validateMtuConfig(Integer publicMtu, Integer privateMtu, Long zoneId) {
        Integer vrMaxMtuForPublicIfaces = NetworkService.VRPublicInterfaceMtu.valueIn(zoneId);
        Integer vrMaxMtuForPrivateIfaces = NetworkService.VRPrivateInterfaceMtu.valueIn(zoneId);
        if (!NetworkService.AllowUsersToSpecifyVRMtu.valueIn(zoneId)) {
            privateMtu = vrMaxMtuForPrivateIfaces;
            publicMtu = vrMaxMtuForPublicIfaces;
            return new Pair<>(publicMtu, privateMtu);
        }

        if (publicMtu > vrMaxMtuForPublicIfaces) {
            String subject = "Incorrect MTU configured on network for public interfaces of the VR";
            String message = String.format("Configured MTU for network VR's public interfaces exceeds the upper limit " +
                    "enforced by zone level setting: %s. VR's public interfaces can be configured with a maximum MTU of %s",
                    NetworkService.VRPublicInterfaceMtu.key(), NetworkService.VRPublicInterfaceMtu.valueIn(zoneId));
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
            publicMtu = vrMaxMtuForPublicIfaces;
        } else if (publicMtu < NetworkService.MINIMUM_MTU) {
            String subject = "Incorrect MTU configured on network for public interfaces of the VR";
            String message = String.format("Configured MTU for network VR's public interfaces is lesser than the supported minimum of %s.", NetworkService.MINIMUM_MTU);
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
            publicMtu = NetworkService.MINIMUM_MTU;
        }

        if (privateMtu > vrMaxMtuForPrivateIfaces) {
            String subject = "Incorrect MTU configured on network for private interface of the VR";
            String message = String.format("Configured MTU for network VR's public interfaces exceeds the upper limit " +
                    "enforced by zone level setting: %s. VR's public interfaces can be configured with a maximum MTU of %s",
                    NetworkService.VRPublicInterfaceMtu.key(), NetworkService.VRPublicInterfaceMtu.valueIn(zoneId));
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PRIVATE_IFACE_MTU, zoneId, null, subject, message);
            privateMtu = vrMaxMtuForPrivateIfaces;
        } else if (privateMtu < NetworkService.MINIMUM_MTU) {
            String subject = "Incorrect MTU configured on network for private interfaces of the VR";
            String message = String.format("Configured MTU for network VR's private interfaces is lesser than the supported minimum of %s.", NetworkService.MINIMUM_MTU);
            logger.warn(message);
            alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PRIVATE_IFACE_MTU, zoneId, null, subject, message);
            privateMtu = NetworkService.MINIMUM_MTU;
        }
        return new Pair<>(publicMtu, privateMtu);
    }

    @Override
    public Pair<Integer, Integer> validateMtuOnUpdate(NetworkVO network, Long zoneId, Integer publicMtu, Integer privateMtu) {
        if (!NetworkService.AllowUsersToSpecifyVRMtu.valueIn(zoneId)) {
            return new Pair<>(null, null);
        }

        if (publicMtu != null) {
            if (publicMtu > NetworkService.VRPublicInterfaceMtu.valueIn(zoneId)) {
                publicMtu = NetworkService.VRPublicInterfaceMtu.valueIn(zoneId);
            } else if (publicMtu < NetworkService.MINIMUM_MTU) {
                String subject = "Incorrect MTU configured on network for public interfaces of the VR";
                String message = String.format("Configured MTU for network VR's public interfaces is lesser than the supported minimum of %s.", NetworkService.MINIMUM_MTU);
                logger.warn(message);
                alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU, zoneId, null, subject, message);
                publicMtu = NetworkService.MINIMUM_MTU;
            }
        }

        if (privateMtu != null) {
            if (privateMtu > NetworkService.VRPrivateInterfaceMtu.valueIn(zoneId)) {
                privateMtu = NetworkService.VRPrivateInterfaceMtu.valueIn(zoneId);
            } else if (privateMtu < NetworkService.MINIMUM_MTU) {
                String subject = "Incorrect MTU configured on network for private interfaces of the VR";
                String message = String.format("Configured MTU for network VR's private interfaces is lesser than the supported minimum of %s.", NetworkService.MINIMUM_MTU);
                logger.warn(message);
                alertManager.sendAlert(AlertService.AlertType.ALERT_TYPE_VR_PRIVATE_IFACE_MTU, zoneId, null, subject, message);
                privateMtu = NetworkService.MINIMUM_MTU;
            }
        }

        if (publicMtu != null && network.getVpcId() != null) {
            logger.warn("Cannot update VPC public interface MTU via network tiers. " +
                    "Please update the public interface MTU via the VPC. Skipping.. ");
            publicMtu = null;
        }

        return new Pair<>(publicMtu, privateMtu);
    }

    @Override
    public void updateNetworkMtu(NetworkVO network, long networkId, Long zoneId, Integer publicMtu, Integer privateMtu, boolean restartNetwork) {
        Pair<Integer, Integer> mtus = validateMtuOnUpdate(network, zoneId, publicMtu, privateMtu);
        publicMtu = mtus.first();
        privateMtu = mtus.second();

        // List all routers for the given network:
        List<DomainRouterVO> routers = routerDao.findByNetwork(networkId);

        // Create Map to store the IPAddress List for each router
        Map<Long, Set<IpAddressTO>> routersToIpList = new HashMap<>();
        for (DomainRouterVO routerVO : routers) {
            Set<IpAddressTO> ips = new HashSet<>();
            List<DomainRouterJoinVO> routerJoinVOS = routerJoinDao.getRouterByIdAndTrafficType(routerVO.getId(), TrafficType.Guest, TrafficType.Public);
            for (DomainRouterJoinVO router : routerJoinVOS) {
                IpAddressTO ip = null;
                if (router.getTrafficType() == TrafficType.Guest && privateMtu != null) {
                    ip = new IpAddressTO(router.getIpAddress(), privateMtu, router.getNetmask());
                    ip.setTrafficType(TrafficType.Guest);
                } else if (router.getTrafficType() == TrafficType.Public && publicMtu != null) {
                    ip = new IpAddressTO(router.getIpAddress(), publicMtu, router.getNetmask());
                    ip.setTrafficType(TrafficType.Public);
                }
                if (ip != null) {
                    ips.add(ip);
                }
            }
            if (network.getGuestType() == GuestType.Isolated && network.getVpcId() == null && publicMtu != null) {
                List<IPAddressVO> addrs = _ipAddressDao.listByNetworkId(networkId);
                for (IPAddressVO addr : addrs) {
                    VlanVO vlan = _vlanDao.findById(addr.getVlanId());
                    IpAddressTO to = new IpAddressTO(addr.getAddress().addr(), publicMtu, vlan.getVlanNetmask());
                    ips.add(to);
                }
            }
            if (!ips.isEmpty()) {
                routersToIpList.put(routerVO.getId(), ips);
            }
        }

        if (!routersToIpList.isEmpty() && !restartNetwork) {
            boolean success = updateMtuOnVr(routersToIpList);
            if (success) {
                updateNetworkDetails(routersToIpList, network, publicMtu, privateMtu);
            } else {
                throw new CloudRuntimeException("Failed to update MTU on the network");
            }
        }
    }

    protected void updateNetworkDetails(Map<Long, Set<IpAddressTO>> routerToIpList, NetworkVO network, Integer publicMtu, Integer privateMtu) {
        for (Map.Entry<Long, Set<IpAddressTO>> routerEntrySet : routerToIpList.entrySet()) {
            for (IpAddressTO ipAddress : routerEntrySet.getValue()) {
                NicVO nicVO = _nicDao.findByInstanceIdAndIpAddressAndVmtype(routerEntrySet.getKey(), ipAddress.getPublicIp(), VirtualMachine.Type.DomainRouter);
                if (nicVO != null) {
                    if (ipAddress.getTrafficType() == TrafficType.Guest) {
                        nicVO.setMtu(privateMtu);
                    } else {
                        nicVO.setMtu(publicMtu);
                    }
                    _nicDao.update(nicVO.getId(), nicVO);
                }
            }
        }

        if (publicMtu != null) {
            network.setPublicMtu(publicMtu);
        }
        if (privateMtu != null) {
            network.setPrivateMtu(privateMtu);
        }
        _networksDao.update(network.getId(), network);
    }

    @Override
    public boolean updateMtuOnVr(Map<Long, Set<IpAddressTO>> routersToIpList) {
        boolean success = false;
        for (Map.Entry<Long, Set<IpAddressTO>> routerEntrySet : routersToIpList.entrySet()) {
            Long routerId = routerEntrySet.getKey();
            DomainRouterVO router = routerDao.findById(routerId);
            if (router == null) {
                logger.error(String.format("Failed to find router with id: %s", routerId));
                continue;
            }
            Commands cmds = new Commands(Command.OnError.Stop);
            Map<String, String> state = new HashMap<>();
            Set<IpAddressTO> ips = routerEntrySet.getValue();
            state.put(ApiConstants.REDUNDANT_STATE, router.getRedundantState() != null ? router.getRedundantState().name() : VirtualRouter.RedundantState.UNKNOWN.name());
            ips.forEach(ip -> ip.setDetails(state));
            commandSetupHelper.setupUpdateNetworkCommands(router, ips, cmds);
            try {
                networkHelper.sendCommandsToRouter(router, cmds);
                Answer updateNetworkAnswer = cmds.getAnswer("updateNetwork");
                if (!(updateNetworkAnswer != null && updateNetworkAnswer.getResult())) {
                    logger.warn("Unable to update guest network on router " + router);
                    throw new CloudRuntimeException("Failed to update guest network with new MTU");
                }
                success = true;
            } catch (ResourceUnavailableException e) {
                logger.error(String.format("Failed to update network MTU for router %s due to %s", router, e.getMessage()));
                success = false;
            }
        }
        return success;
    }
}
