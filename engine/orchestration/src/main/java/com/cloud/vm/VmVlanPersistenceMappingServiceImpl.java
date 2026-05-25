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

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.network.Network;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.Pair;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmVlanPersistenceMappingServiceImpl implements VmVlanPersistenceMappingService {

    @Inject
    protected UserVmJoinDao userVmJoinDao;
    @Inject
    protected DomainRouterJoinDao domainRouterJoinDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected VMInstanceDao vmDao;

    @Override
    public Map<String, Boolean> getVlanToPersistenceMapForVM(long vmId) {
        List<UserVmJoinVO> userVmJoinVOs = userVmJoinDao.searchByIds(vmId);
        Map<String, Boolean> vlanToPersistenceMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(userVmJoinVOs)) {
            for (UserVmJoinVO userVmJoinVO : userVmJoinVOs) {
                NetworkVO networkVO = networkDao.findById(userVmJoinVO.getNetworkId());
                updatePersistenceMap(vlanToPersistenceMap, networkVO);
            }
        } else {
            VMInstanceVO vmInstanceVO = vmDao.findById(vmId);
            if (vmInstanceVO != null && vmInstanceVO.getType() == VirtualMachine.Type.DomainRouter) {
                DomainRouterJoinVO routerVO = domainRouterJoinDao.findById(vmId);
                if (routerVO != null) {
                    NetworkVO networkVO = networkDao.findById(routerVO.getNetworkId());
                    updatePersistenceMap(vlanToPersistenceMap, networkVO);
                }
            }
        }
        return vlanToPersistenceMap;
    }

    private void updatePersistenceMap(Map<String, Boolean> vlanToPersistenceMap, NetworkVO networkVO) {
        if (networkVO == null) {
            return;
        }
        NetworkOfferingVO offeringVO = networkOfferingDao.findById(networkVO.getNetworkOfferingId());
        if (offeringVO == null) {
            return;
        }
        Pair<String, Boolean> data = getVMNetworkDetails(networkVO, offeringVO.isPersistent());
        Boolean shouldDeleteNwResource = (MapUtils.isNotEmpty(vlanToPersistenceMap) && data != null) ? vlanToPersistenceMap.get(data.first()) : null;
        if (data != null && (shouldDeleteNwResource == null || shouldDeleteNwResource)) {
            vlanToPersistenceMap.put(data.first(), data.second());
        }
    }

    /**
     *
     * @param networkVO - the network object used to determine the vlanId from the broadcast URI
     * @param isPersistent - indicates if the corresponding network's network offering is Persistent
     *
     * @return <VlanId, ShouldKVMBridgeBeDeleted> - basically returns the vlan ID which is used to determine the
     * bridge name for KVM hypervisor and based on the network and isolation type and persistent setting of the offering
     * we decide whether the bridge is to be deleted (KVM) if the last VM in that host is destroyed / migrated
     */
    private Pair<String, Boolean> getVMNetworkDetails(NetworkVO networkVO, boolean isPersistent) {
        URI broadcastUri = networkVO.getBroadcastUri();
        if (broadcastUri != null) {
            String scheme = broadcastUri.getScheme();
            String vlanId = Networks.BroadcastDomainType.getValue(broadcastUri);
            boolean shouldDelete = !((networkVO.getGuestType() == Network.GuestType.L2 || networkVO.getGuestType() == Network.GuestType.Isolated) &&
                    (scheme != null && scheme.equalsIgnoreCase("vlan"))
                    && isPersistent);
            if (shouldDelete) {
                int persistentNetworksCount = networkDao.getOtherPersistentNetworksCount(networkVO.getId(), networkVO.getBroadcastUri().toString(), true);
                if (persistentNetworksCount > 0) {
                    shouldDelete = false;
                }
            }
            return new Pair<>(vlanId, shouldDelete);
        }
        return null;
    }
}
