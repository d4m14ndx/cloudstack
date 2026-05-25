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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;

@Component
public class NicProfileLifecycleMappingServiceImpl implements NicProfileLifecycleMappingService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NicDao nicDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected NetworkModel networkModel;

    protected List<NetworkGuru> networkGurus;

    public void setNetworkGurus(final List<NetworkGuru> networkGurus) {
        this.networkGurus = networkGurus;
    }

    @Override
    public Integer applyProfileToNic(final NicVO vo, final NicProfile profile, Integer deviceId) {
        if (profile.getDeviceId() != null) {
            vo.setDeviceId(profile.getDeviceId());
        } else if (deviceId != null) {
            vo.setDeviceId(deviceId++);
        }

        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }

        vo.setDefaultNic(profile.isDefaultNic());

        vo.setIPv4Address(profile.getIPv4Address());
        vo.setAddressFormat(profile.getFormat());

        if (profile.getMacAddress() != null) {
            vo.setMacAddress(profile.getMacAddress());
        }

        vo.setMode(profile.getMode());
        vo.setIPv4Netmask(profile.getIPv4Netmask());
        vo.setIPv4Gateway(profile.getIPv4Gateway());

        if (profile.getBroadCastUri() != null) {
            vo.setBroadcastUri(profile.getBroadCastUri());
        }

        if (profile.getIsolationUri() != null) {
            vo.setIsolationUri(profile.getIsolationUri());
        }

        vo.setState(Nic.State.Allocated);

        vo.setIPv6Address(profile.getIPv6Address());
        vo.setIPv6Gateway(profile.getIPv6Gateway());
        vo.setIPv6Cidr(profile.getIPv6Cidr());

        return deviceId;
    }

    @Override
    public void applyProfileToNicForRelease(final NicVO vo, final NicProfile profile) {
        vo.setIPv4Gateway(profile.getIPv4Gateway());
        vo.setAddressFormat(profile.getFormat());
        vo.setIPv4Address(profile.getIPv4Address());
        vo.setIPv6Address(profile.getIPv6Address());
        vo.setMacAddress(profile.getMacAddress());
        if (profile.getReservationStrategy() != null) {
            vo.setReservationStrategy(profile.getReservationStrategy());
        }
        vo.setBroadcastUri(profile.getBroadCastUri());
        vo.setIsolationUri(profile.getIsolationUri());
        vo.setIPv4Netmask(profile.getIPv4Netmask());
    }

    @Override
    public void applyProfileToNetwork(final NetworkVO network, final NetworkProfile profile) {
        network.setBroadcastUri(profile.getBroadcastUri());
        network.setDns1(profile.getDns1());
        network.setDns2(profile.getDns2());
        network.setPhysicalNetworkId(profile.getPhysicalNetworkId());
    }

    @Override
    public NicTO toNicTO(final NicVO nic, final NicProfile profile, final NetworkVO config) {
        final NicTO to = new NicTO();
        to.setDeviceId(nic.getDeviceId());
        to.setBroadcastType(config.getBroadcastDomainType());
        to.setType(config.getTrafficType());
        to.setIp(nic.getIPv4Address());
        to.setNetmask(nic.getIPv4Netmask());
        to.setMac(nic.getMacAddress());
        to.setDns1(profile.getIPv4Dns1());
        to.setDns2(profile.getIPv4Dns2());
        if (nic.getIPv4Gateway() != null) {
            to.setGateway(nic.getIPv4Gateway());
        } else {
            to.setGateway(config.getGateway());
        }
        if (nic.getVmType() != VirtualMachine.Type.User) {
            to.setPxeDisable(true);
        }
        to.setDefaultNic(nic.isDefaultNic());
        to.setBroadcastUri(nic.getBroadcastUri());
        to.setIsolationuri(nic.getIsolationUri());
        if (profile != null) {
            to.setDns1(profile.getIPv4Dns1());
            to.setDns2(profile.getIPv4Dns2());
        }

        final Integer networkRate = networkModel.getNetworkRate(config.getId(), null);
        to.setNetworkRateMbps(networkRate);

        to.setUuid(config.getUuid());

        return to;
    }

    @Override
    public NicProfile getNicProfileForVm(final Network network, final NicProfile requested, final VirtualMachine vm) {
        NicProfile nic = null;
        if (requested != null && requested.getBroadCastUri() != null) {
            final String broadcastUri = requested.getBroadCastUri().toString();
            final String ipAddress = requested.getIPv4Address();
            final NicVO nicVO = nicDao.findByNetworkIdInstanceIdAndBroadcastUri(network.getId(), vm.getId(), broadcastUri);
            if (nicVO != null) {
                if (ipAddress == null || nicVO.getIPv4Address().equals(ipAddress)) {
                    nic = networkModel.getNicProfile(vm, network.getId(), broadcastUri);
                }
            }
        } else {
            final NicVO nicVO = nicDao.findByNtwkIdAndInstanceId(network.getId(), vm.getId());
            if (nicVO != null) {
                nic = networkModel.getNicProfile(vm, network.getId(), null);
            }
        }
        return nic;
    }

    @Override
    public boolean getNicProfileDefaultNic(NicProfile nicProfile) {
        if (nicProfile != null) {
            logger.debug("Using requested nic profile isDefaultNic value [{}].", nicProfile.isDefaultNic());
            return nicProfile.isDefaultNic();
        }

        logger.debug("Using isDefaultNic default value [false] as requested nic profile is null.");
        return false;
    }

    @Override
    public List<NicProfile> getNicProfiles(final Long vmId, HypervisorType hypervisorType) {
        final List<NicVO> nics = nicDao.listByVmId(vmId);
        final List<NicProfile> profiles = new ArrayList<>();

        if (nics != null) {
            for (final Nic nic : nics) {
                final NetworkVO network = networksDao.findById(nic.getNetworkId());
                final Integer networkRate = networkModel.getNetworkRate(network.getId(), vmId);

                final NetworkGuru guru = AdapterBase.getAdapterByName(networkGurus, network.getGuruName());
                final NicProfile profile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), networkRate,
                        networkModel.isSecurityGroupSupportedInNetwork(network), networkModel.getNetworkTag(hypervisorType, network));
                guru.updateNicProfile(profile, network);
                profiles.add(profile);
            }
        }
        return profiles;
    }

    @Override
    public List<NicProfile> getNicProfiles(final VirtualMachine vm) {
        return getNicProfiles(vm.getId(), vm.getHypervisorType());
    }

    @Override
    public Map<String, String> getSystemVMAccessDetails(final VirtualMachine vm) {
        final Map<String, String> accessDetails = new HashMap<>();
        accessDetails.put(NetworkElementCommand.ROUTER_NAME, vm.getInstanceName());
        String privateIpAddress = null;
        for (final NicProfile profile : getNicProfiles(vm)) {
            if (profile == null) {
                continue;
            }
            final Network network = networksDao.findById(profile.getNetworkId());
            if (network == null) {
                continue;
            }
            final String address = profile.getIPv4Address();
            if (network.getTrafficType() == Networks.TrafficType.Control) {
                accessDetails.put(NetworkElementCommand.ROUTER_IP, address);
            }
            if (network.getTrafficType() == Networks.TrafficType.Guest) {
                accessDetails.put(NetworkElementCommand.ROUTER_GUEST_IP, address);
            }
            if (network.getTrafficType() == Networks.TrafficType.Management) {
                privateIpAddress = address;
            }
            if (network.getTrafficType() != null && StringUtils.isNotEmpty(address)) {
                accessDetails.put(network.getTrafficType().name(), address);
            }
        }

        if (privateIpAddress != null && StringUtils.isEmpty(accessDetails.get(NetworkElementCommand.ROUTER_IP))) {
            accessDetails.put(NetworkElementCommand.ROUTER_IP,  privateIpAddress);
        }
        return accessDetails;
    }
}
