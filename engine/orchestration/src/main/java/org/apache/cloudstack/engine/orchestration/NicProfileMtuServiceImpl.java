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

import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;

/**
 * Resolves and applies virtual-router NIC MTU values -- extracted from
 * {@link NetworkOrchestrator}.
 *
 * @see NicProfileMtuService
 */
@Component
public class NicProfileMtuServiceImpl implements NicProfileMtuService {

    @Inject
    protected DomainRouterJoinDao routerJoinDao;

    @Inject
    protected NetworkDao networksDao;

    @Inject
    protected EntityManager entityManager;

    @Override
    public Pair<NetworkVO, VpcVO> getGuestNetworkRouterAndVpcDetails(long routerId) {
        List<DomainRouterJoinVO> routerVo = routerJoinDao.getRouterByIdAndTrafficType(routerId, TrafficType.Guest);
        if (routerVo.isEmpty()) {
            routerVo = routerJoinDao.getRouterByIdAndTrafficType(routerId, TrafficType.Public);
            if (routerVo.isEmpty()) {
                return null;
            }
        }
        DomainRouterJoinVO guestRouterDetails = routerVo.get(0);
        VpcVO vpc = null;
        if (guestRouterDetails.getVpcId() != 0) {
            vpc = entityManager.findById(VpcVO.class, guestRouterDetails.getVpcId());
        }
        long networkId = guestRouterDetails.getNetworkId();
        return new Pair<>(networksDao.findById(networkId), vpc);
    }

    @Override
    public void setMtuDetailsInVRNic(final Pair<NetworkVO, VpcVO> networks, Network network, NicVO vo) {
        if (TrafficType.Public == network.getTrafficType()) {
            if (networks == null) {
                return;
            }
            NetworkVO networkVO = networks.first();
            VpcVO vpcVO = networks.second();
            if (vpcVO != null) {
                vo.setMtu(vpcVO.getPublicMtu());
            } else {
                vo.setMtu(networkVO.getPublicMtu());
            }
        } else if (TrafficType.Guest == network.getTrafficType()) {
            vo.setMtu(network.getPrivateMtu());
        }
    }

    @Override
    public void setMtuInVRNicProfile(final Pair<NetworkVO, VpcVO> networks, TrafficType trafficType, NicProfile vmNic) {
        if (networks == null) {
            return;
        }
        NetworkVO networkVO = networks.first();
        VpcVO vpcVO = networks.second();
        if (networkVO != null) {
            if (TrafficType.Public == trafficType) {
                if (vpcVO != null) {
                    vmNic.setMtu(vpcVO.getPublicMtu());
                } else {
                    vmNic.setMtu(networkVO.getPublicMtu());
                }
            } else if (TrafficType.Guest == trafficType) {
                vmNic.setMtu(networkVO.getPrivateMtu());
            }
        }
    }
}
