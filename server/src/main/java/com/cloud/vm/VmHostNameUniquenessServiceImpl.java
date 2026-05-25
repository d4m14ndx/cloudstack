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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * VM hostname / network-domain uniqueness + extra-DHCP-option network
 * validation — extracted from {@link UserVmManagerImpl}.
 *
 * @see VmHostNameUniquenessService
 */
@Component
public class VmHostNameUniquenessServiceImpl implements VmHostNameUniquenessService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private DomainDao domainDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private VMInstanceDao vmInstanceDao;

    @Override
    public void checkIfHostNameUniqueInNtwkDomain(String hostName, List<NetworkVO> networkList) {
        // Check that hostName is unique
        Map<String, Set<Long>> ntwkDomains = getNetworkIdPerNetworkDomain(networkList);
        for (Entry<String, Set<Long>> ntwkDomain : ntwkDomains.entrySet()) {
            for (Long ntwkId : ntwkDomain.getValue()) {
                // * get all vms hostNames in the network
                List<String> hostNames = vmInstanceDao.listDistinctHostNames(ntwkId);
                // * verify that there are no duplicates
                if (hostNames.contains(hostName)) {
                    throw new InvalidParameterValueException("The vm with hostName " + hostName + " already exists in the network domain: " + ntwkDomain.getKey() + "; network="
                            + ((networkModel.getNetwork(ntwkId) != null) ? networkModel.getNetwork(ntwkId).getName() : "<unknown>"));
                }
            }
        }
    }

    @Override
    public void verifyExtraDhcpOptionsNetwork(Map<String, Map<Integer, String>> dhcpOptionsMap, List<NetworkVO> networkList) {
        if (dhcpOptionsMap != null) {
            for (String networkUuid : dhcpOptionsMap.keySet()) {
                boolean networkFound = false;
                for (NetworkVO network : networkList) {
                    if (network.getUuid().equals(networkUuid)) {
                        networkFound = true;
                        break;
                    }
                }

                if (!networkFound) {
                    throw new InvalidParameterValueException("VM does not has a nic in the Network (" + networkUuid + ") that is specified in the extra dhcp options.");
                }
            }
        }
    }

    @Override
    public Map<String, Set<Long>> getNetworkIdPerNetworkDomain(List<NetworkVO> networkList) {
        Map<String, Set<Long>> ntwkDomains = new HashMap<>();

        List<NetworkVO> updatedNetworkList = getNetworksForCheckUniqueHostName(networkList);
        for (Network network : updatedNetworkList) {
            String ntwkDomain = network.getNetworkDomain();
            Set<Long> ntwkIds;
            if (!ntwkDomains.containsKey(ntwkDomain)) {
                ntwkIds = new HashSet<>();
            } else {
                ntwkIds = ntwkDomains.get(ntwkDomain);
            }
            ntwkIds.add(network.getId());
            ntwkDomains.put(ntwkDomain, ntwkIds);
        }
        return ntwkDomains;
    }

    @Override
    public List<NetworkVO> getNetworksForCheckUniqueHostName(List<NetworkVO> networkList) {
        List<NetworkVO> finalNetworkList;
        Set<String> uniqueNtwkDomains;
        switch (getVmDistinctHostNameScope()) {
            case "global":
                uniqueNtwkDomains = networkList.stream().map(NetworkVO::getNetworkDomain).collect(Collectors.toSet());
                finalNetworkList = networkDao.listByNetworkDomains(uniqueNtwkDomains);
                break;
            case "domain":
                finalNetworkList = getNetworksWithSameNetworkDomainInDomains(networkList, false);
                break;
            case "subdomain":
                finalNetworkList = getNetworksWithSameNetworkDomainInDomains(networkList, true);
                break;
            case "account":
                uniqueNtwkDomains = networkList.stream().map(NetworkVO::getNetworkDomain).collect(Collectors.toSet());
                Set<Long> accountIds = networkList.stream().map(Network::getAccountId).collect(Collectors.toSet());
                finalNetworkList = networkDao.listByNetworkDomainsAndAccountIds(uniqueNtwkDomains, accountIds);
                break;
            default:
                Set<Long> vpcIds = networkList.stream().map(Network::getVpcId).filter(Objects::nonNull).collect(Collectors.toSet());
                finalNetworkList = new ArrayList<>(networkList);
                for (Long vpcId : vpcIds) {
                    finalNetworkList.addAll(networkDao.listByVpc(vpcId));
                }
                break;
        }
        return finalNetworkList;
    }

    @Override
    public List<NetworkVO> getNetworksWithSameNetworkDomainInDomains(List<NetworkVO> networkList, boolean checkSubDomains) {
        Set<String> uniqueNtwkDomains = networkList.stream().map(NetworkVO::getNetworkDomain).collect(Collectors.toSet());
        Set<Long> domainIdList = new HashSet<>();
        for (Network network : networkList) {
            domainIdList.add(network.getDomainId());
        }
        Set<Long> finalDomainIdSet = new HashSet<>(domainIdList);
        if (checkSubDomains) {
            for (Long domainId : domainIdList) {
                DomainVO domain = domainDao.findById(domainId);
                List<Long> childDomainIds = domainDao.getDomainChildrenIds(domain.getPath());
                finalDomainIdSet.addAll(childDomainIds);
            }
        }
        return networkDao.listByNetworkDomainsAndDomainIds(uniqueNtwkDomains, finalDomainIdSet);
    }

    /**
     * Encapsulates the lookup of {@code vm.distinct.hostname.scope} so
     * unit tests can stub it without touching the static
     * {@link UserVmManager#VmDistinctHostNameScope} config.
     */
    protected String getVmDistinctHostNameScope() {
        return UserVmManager.VmDistinctHostNameScope.value();
    }
}
