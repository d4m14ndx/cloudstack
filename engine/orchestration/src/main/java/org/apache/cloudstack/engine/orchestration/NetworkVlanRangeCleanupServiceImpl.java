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

import static com.cloud.configuration.ConfigurationManager.MESSAGE_DELETE_VLAN_IP_RANGE_EVENT;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

@Component
public class NetworkVlanRangeCleanupServiceImpl implements NetworkVlanRangeCleanupService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected VlanDao vlanDao;
    @Inject
    protected ConfigurationManager configurationManager;
    @Inject
    protected PrivateIpDao privateIpDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected DataCenterDao dataCenterDao;
    @Inject
    protected NetworkOfferingVlanValidationService networkOfferingVlanValidationService;
    @Inject
    protected MessageBus messageBus;

    @Override
    public Pair<Boolean, List<VlanVO>> deleteVlansInNetwork(final NetworkVO network, final long userId, final Account callerAccount) {
        final long networkId = network.getId();
        final List<VlanVO> publicVlans = vlanDao.listVlansByNetworkId(networkId);
        List<VlanVO> deletedPublicVlanRange = new ArrayList<>();
        boolean result = true;
        for (final VlanVO vlan : publicVlans) {
            VlanVO vlanRange = configurationManager.deleteVlanAndPublicIpRange(userId, vlan.getId(), callerAccount);
            if (vlanRange == null) {
                logger.warn("Failed to delete vlan [id: {}, uuid: {}];", vlan.getId(), vlan.getUuid());
                result = false;
            } else {
                deletedPublicVlanRange.add(vlanRange);
            }
        }

        final int privateIpAllocCount = privateIpDao.countAllocatedByNetworkId(networkId);
        if (privateIpAllocCount > 0) {
            logger.warn("Can't delete Private IP range for Network {} as it has allocated IP addresses", network);
            result = false;
        } else {
            privateIpDao.deleteByNetworkId(networkId);
            logger.debug("Deleted ip range for private network {}", network);
        }

        if (networkOfferingVlanValidationService.isSharedNetworkWithoutSpecifyVlan(networkOfferingDao.findById(network.getNetworkOfferingId()))) {
            logger.debug("Releasing vnet for the network {}", network);
            dataCenterDao.releaseVnet(BroadcastDomainType.getValue(network.getBroadcastUri()), network.getDataCenterId(),
                    network.getPhysicalNetworkId(), network.getAccountId(), network.getReservationId());
        }
        return new Pair<>(result, deletedPublicVlanRange);
    }

    @Override
    public void publishDeletedVlanRanges(String senderAddress, List<VlanVO> deletedVlanRangeToPublish) {
        if (CollectionUtils.isNotEmpty(deletedVlanRangeToPublish)) {
            for (VlanVO vlan : deletedVlanRangeToPublish) {
                messageBus.publish(senderAddress, MESSAGE_DELETE_VLAN_IP_RANGE_EVENT, PublishScope.LOCAL, vlan);
            }
        }
    }
}
