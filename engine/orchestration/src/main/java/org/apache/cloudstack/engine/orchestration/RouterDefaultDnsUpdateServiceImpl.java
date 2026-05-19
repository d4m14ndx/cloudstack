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

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.RouterNetworkDao;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.DomainRouterDao;

@Component
public class RouterDefaultDnsUpdateServiceImpl implements RouterDefaultDnsUpdateService {

    @Inject
    protected DomainRouterDao routerDao;
    @Inject
    protected RouterNetworkDao routerNetworkDao;
    @Inject
    protected VpcManager vpcManager;
    @Inject
    protected NetworkDao networksDao;

    @Override
    public void updateRouterDefaultDns(final VirtualMachineProfile vmProfile, final NicProfile nicProfile) {
        if (!Type.DomainRouter.equals(vmProfile.getType()) || !nicProfile.isDefaultNic()) {
            return;
        }
        DomainRouterVO router = routerDao.findById(vmProfile.getId());
        if (router != null && router.getVpcId() != null) {
            final Vpc vpc = vpcManager.getActiveVpc(router.getVpcId());
            if (StringUtils.isNotBlank(vpc.getIp4Dns1())) {
                nicProfile.setIPv4Dns1(vpc.getIp4Dns1());
                nicProfile.setIPv4Dns2(vpc.getIp4Dns2());
            }
            if (StringUtils.isNotBlank(vpc.getIp6Dns1())) {
                nicProfile.setIPv6Dns1(vpc.getIp6Dns1());
                nicProfile.setIPv6Dns2(vpc.getIp6Dns2());
            }
            return;
        }
        List<Long> networkIds = routerNetworkDao.getRouterNetworks(vmProfile.getId());
        if (CollectionUtils.isEmpty(networkIds) || networkIds.size() > 1) {
            return;
        }
        final NetworkVO routerNetwork = networksDao.findById(networkIds.get(0));
        if (StringUtils.isNotBlank(routerNetwork.getDns1())) {
            nicProfile.setIPv4Dns1(routerNetwork.getDns1());
            nicProfile.setIPv4Dns2(routerNetwork.getDns2());
        }
        if (StringUtils.isNotBlank(routerNetwork.getIp6Dns1())) {
            nicProfile.setIPv6Dns1(routerNetwork.getIp6Dns1());
            nicProfile.setIPv6Dns2(routerNetwork.getIp6Dns2());
        }
    }
}
