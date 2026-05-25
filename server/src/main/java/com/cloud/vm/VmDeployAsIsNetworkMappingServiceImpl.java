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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.deployasis.OVFNetworkTO;
import com.cloud.dc.DataCenter;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

@Component
public class VmDeployAsIsNetworkMappingServiceImpl implements VmDeployAsIsNetworkMappingService {

    @Inject
    private TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Inject
    private NetworkModel networkModel;

    @Override
    public LinkedHashMap<Integer, Long> getDeployAsIsVmNetworkMapping(DataCenter zone, Account owner,
            VirtualMachineTemplate template, Map<Integer, Long> vmNetworkMapping,
            DefaultNetworkProvider defaultNetworkProvider)
            throws InsufficientCapacityException, ResourceAllocationException {
        LinkedHashMap<Integer, Long> mapping = new LinkedHashMap<>();
        if (ImageFormat.OVA.equals(template.getFormat())) {
            List<OVFNetworkTO> OVFNetworkTOList =
                    templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(template.getId());
            if (CollectionUtils.isNotEmpty(OVFNetworkTOList)) {
                Network lastMappedNetwork = null;
                for (OVFNetworkTO OVFNetworkTO : OVFNetworkTOList) {
                    Long networkId = vmNetworkMapping.get(OVFNetworkTO.getInstanceID());
                    if (networkId == null && lastMappedNetwork == null) {
                        lastMappedNetwork = getNetworkForDeployAsIsOvfNetworkMapping(zone, owner, defaultNetworkProvider);
                    }
                    if (networkId == null) {
                        networkId = lastMappedNetwork.getId();
                    }
                    mapping.put(OVFNetworkTO.getInstanceID(), networkId);
                }
            }
        }
        return mapping;
    }

    private Network getNetworkForDeployAsIsOvfNetworkMapping(DataCenter zone, Account owner,
            DefaultNetworkProvider defaultNetworkProvider)
            throws InsufficientCapacityException, ResourceAllocationException {
        Network network = null;
        if (zone.isSecurityGroupEnabled() || networkModel.isSecurityGroupSupportedForZone(zone.getId())) {
            network = networkModel.getNetworkWithSGWithFreeIPs(owner, zone.getId());
            if (network == null) {
                throw new InvalidParameterValueException("No network with security enabled is found in zone ID: " + zone.getUuid());
            }
        } else {
            network = defaultNetworkProvider.getDefaultNetwork(zone, owner, true);
            if (network == null) {
                throw new InvalidParameterValueException(String.format("Default network not found for zone ID: %s and account ID: %s", zone.getUuid(), owner.getUuid()));
            }
        }
        return network;
    }
}
