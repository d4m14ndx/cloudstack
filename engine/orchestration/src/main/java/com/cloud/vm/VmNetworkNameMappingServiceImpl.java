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

package com.cloud.vm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.user.Account;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class VmNetworkNameMappingServiceImpl implements VmNetworkNameMappingService {

    @Inject
    protected UserVmJoinDao userVmJoinDao;
    @Inject
    protected DomainRouterJoinDao domainRouterJoinDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected DomainDao domainDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected VpcDao vpcDao;

    @Override
    public void setVmNetworkDetails(VMInstanceVO vm, VirtualMachineTO vmTO) {
        Map<Long, String> networkToNetworkNameMap = new HashMap<>();
        if (VirtualMachine.Type.User.equals(vm.getType())) {
            List<UserVmJoinVO> userVmJoinVOs = userVmJoinDao.searchByIds(vm.getId());
            if (userVmJoinVOs != null && !userVmJoinVOs.isEmpty()) {
                for (UserVmJoinVO userVmJoinVO : userVmJoinVOs) {
                    addToNetworkNameMap(userVmJoinVO.getNetworkId(), vm.getDataCenterId(), networkToNetworkNameMap);
                }
                vmTO.setNetworkIdToNetworkNameMap(networkToNetworkNameMap);
            }
        } else if (VirtualMachine.Type.DomainRouter.equals(vm.getType())) {
            List<DomainRouterJoinVO> routerJoinVO = domainRouterJoinDao.getRouterByIdAndTrafficType(vm.getId(), Networks.TrafficType.Guest);
            for (DomainRouterJoinVO router : routerJoinVO) {
                NetworkVO guestNetwork = networkDao.findById(router.getNetworkId());
                if (guestNetwork.getVpcId() == null && guestNetwork.getBroadcastDomainType() == Networks.BroadcastDomainType.NSX) {
                    addToNetworkNameMap(router.getNetworkId(), vm.getDataCenterId(), networkToNetworkNameMap);
                }
            }
            vmTO.setNetworkIdToNetworkNameMap(networkToNetworkNameMap);
        }
    }

    private void addToNetworkNameMap(long networkId, long dataCenterId, Map<Long, String> networkToNetworkNameMap) {
        NetworkVO networkVO = networkDao.findById(networkId);
        Account acc = accountDao.findById(networkVO.getAccountId());
        Domain domain = domainDao.findById(networkVO.getDomainId());
        DataCenter zone = dataCenterDao.findById(dataCenterId);
        if (Objects.isNull(zone)) {
            throw new CloudRuntimeException(String.format("Failed to find zone with ID: %s", dataCenterId));
        }
        if (Objects.isNull(acc)) {
            throw new CloudRuntimeException(String.format("Failed to find account with ID: %s", networkVO.getAccountId()));
        }
        if (Objects.isNull(domain)) {
            throw new CloudRuntimeException(String.format("Failed to find domain with ID: %s", networkVO.getDomainId()));
        }
        String networkName = String.format("D%s-A%s-Z%s", domain.getId(), acc.getId(), zone.getId());
        if (Objects.isNull(networkVO.getVpcId())) {
            networkName += "-S" + networkVO.getId();
        } else {
            VpcVO vpc = vpcDao.findById(networkVO.getVpcId());
            if (Objects.isNull(vpc)) {
                throw new CloudRuntimeException(String.format("Failed to find VPC with ID: %s", networkVO.getVpcId()));
            }
            networkName = String.format("%s-V%s-S%s", networkName, vpc.getId(), networkVO.getId());
        }
        networkToNetworkNameMap.put(networkVO.getId(), networkName);
    }
}
