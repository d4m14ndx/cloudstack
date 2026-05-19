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

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;

@Component
public class RequestedNicIpReservationServiceImpl implements RequestedNicIpReservationService {

    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected IPAddressDao ipAddressDao;
    @Inject
    protected NetworkModel networkModel;

    /**
     * If the requested IPv4 address from the NicProfile was configured then it configures the IPv4 address, Netmask and Gateway to deploy the VM with the requested IP.
     */
    @Override
    public void configureNicProfileBasedOnRequestedIp(NicProfile requestedNicProfile, NicProfile nicProfile, Network network) {
        if (requestedNicProfile == null) {
            return;
        }
        String requestedIpv4Address = requestedNicProfile.getRequestedIPv4();
        if (requestedIpv4Address == null) {
            return;
        }
        if (!NetUtils.isValidIp4(requestedIpv4Address)) {
            throw new InvalidParameterValueException(String.format("The requested [IPv4 address='%s'] is not a valid IP address", requestedIpv4Address));
        }

        VlanVO vlanVo = vlanDao.findByNetworkIdAndIpv4(network.getId(), requestedIpv4Address);
        if (vlanVo == null) {
            throw new InvalidParameterValueException(String.format("Trying to configure a Nic with the requested [IPv4='%s'] but cannot find a Vlan for the [network '%s']",
                    requestedIpv4Address, network));
        }

        String ipv4Gateway = vlanVo.getVlanGateway();
        String ipv4Netmask = vlanVo.getVlanNetmask();

        if (!NetUtils.isValidIp4(ipv4Gateway)) {
            throw new InvalidParameterValueException(String.format("The [IPv4Gateway='%s'] from [Vlan id=%d uuid=%s] is not valid", ipv4Gateway, vlanVo.getId(), vlanVo.getUuid()));
        }
        if (!NetUtils.isValidIp4Netmask(ipv4Netmask)) {
            throw new InvalidParameterValueException(String.format("The [IPv4Netmask='%s'] from [Vlan id=%d uuid=%s] is not valid", ipv4Netmask, vlanVo.getId(), vlanVo.getUuid()));
        }

        acquireLockAndCheckIfIpv4IsFree(network, requestedIpv4Address);

        nicProfile.setIPv4Address(requestedIpv4Address);
        nicProfile.setIPv4Gateway(ipv4Gateway);
        nicProfile.setIPv4Netmask(ipv4Netmask);

        if (nicProfile.getMacAddress() == null || !networkModel.isMACUnique(nicProfile.getMacAddress(), network.getId())) {
            try {
                String macAddress = networkModel.getNextAvailableMacAddressInNetwork(network.getId());
                nicProfile.setMacAddress(macAddress);
            } catch (InsufficientAddressCapacityException e) {
                throw new CloudRuntimeException(String.format("Cannot get next available mac address in [network %s]", network), e);
            }
        }
    }

    /**
     * Acquires lock in "user_ip_address" and checks if the requested IPv4 address is Free.
     */
    @Override
    public void acquireLockAndCheckIfIpv4IsFree(Network network, String requestedIpv4Address) {
        IPAddressVO ipVO = ipAddressDao.findByIpAndSourceNetworkId(network.getId(), requestedIpv4Address);
        if (ipVO == null) {
            throw new InvalidParameterValueException(
                    String.format("Cannot find IPAddressVO for guest [IPv4 address='%s'] and [network %s]", requestedIpv4Address, network));
        }
        try {
            IPAddressVO lockedIpVO = ipAddressDao.acquireInLockTable(ipVO.getId());
            validateLockedRequestedIp(ipVO, lockedIpVO);
            lockedIpVO.setState(IPAddressVO.State.Allocated);
            lockedIpVO.setAllocatedTime(new Date());
            ipAddressDao.update(lockedIpVO.getId(), lockedIpVO);
        } finally {
            ipAddressDao.releaseFromLockTable(ipVO.getId());
        }
    }

    /**
     * Validates the locked IP, throwing an exception if the locked IP is null or the locked IP is not in 'Free' state.
     */
    @Override
    public void validateLockedRequestedIp(IPAddressVO ipVO, IPAddressVO lockedIpVO) {
        if (lockedIpVO == null) {
            throw new InvalidParameterValueException(String.format("Cannot acquire guest [IPv4 address='%s'] as it was removed while acquiring lock", ipVO.getAddress()));
        }
        if (lockedIpVO.getState() != IPAddressVO.State.Free) {
            throw new InvalidParameterValueException(
                    String.format("Cannot acquire guest [IPv4 address='%s']; The Ip address is in [state='%s']", ipVO.getAddress(), lockedIpVO.getState().toString()));
        }
    }
}
