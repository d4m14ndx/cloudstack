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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class NetworkOfferingIpCompatibilityServiceImpl implements NetworkOfferingIpCompatibilityService {

    @Inject
    NetworkModel networkModel;
    @Inject
    NetworkOfferingDao networkOfferingDao;
    @Inject
    FirewallRulesDao firewallRulesDao;

    @Override
    public boolean canIpsUseOffering(List<PublicIp> publicIps, long offeringId) {
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(publicIps, false, true);
        Map<Service, Set<Provider>> serviceToProviders = networkModel.getNetworkOfferingServiceProvidersMap(offeringId);
        NetworkOfferingVO offering = networkOfferingDao.findById(offeringId);
        if (offering.isInline()) {
            Provider firewallProvider = null;
            if (serviceToProviders.containsKey(Service.Firewall)) {
                firewallProvider = (Provider)serviceToProviders.get(Service.Firewall).toArray()[0];
            }
            Set<Provider> p = new HashSet<>();
            p.add(firewallProvider);
            serviceToProviders.remove(Service.Lb);
            serviceToProviders.put(Service.Lb, p);
        }
        for (PublicIp ip : ipToServices.keySet()) {
            Set<Service> services = ipToServices.get(ip);
            Provider provider = null;
            for (Service service : services) {
                Set<Provider> curProviders = serviceToProviders.get(service);
                if (curProviders == null || curProviders.isEmpty()) {
                    continue;
                }
                Provider curProvider = (Provider)curProviders.toArray()[0];
                if (provider == null) {
                    provider = curProvider;
                    continue;
                }
                if (!provider.equals(curProvider)) {
                    throw new InvalidParameterValueException("There would be multiple providers for IP " + ip.getAddress() + " with the new network offering!");
                }
            }
        }
        return true;
    }

    @Override
    public boolean canIpUsedForNonConserveService(PublicIp ip, Service service) {
        List<PublicIp> ipList = new ArrayList<>();
        ipList.add(ip);
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(ipList, false, false);
        Set<Service> services = ipToServices.get(ip);
        if (services == null || services.isEmpty()) {
            return true;
        }
        if (services.size() != 1) {
            throw new InvalidParameterValueException("There are multiple services used IP " + ip.getAddress() + ".");
        }
        if (service != null && !((Service)services.toArray()[0] == service || service.equals(Service.Firewall))) {
            throw new InvalidParameterValueException("The IP " + ip.getAddress() + " is already used as " + ((Service)services.toArray()[0]).getName() + " rather than " + service.getName());
        }
        return true;
    }

    @Override
    public boolean canIpsUsedForNonConserve(List<PublicIp> publicIps) {
        boolean result = true;
        for (PublicIp ip : publicIps) {
            result = canIpUsedForNonConserveService(ip, null);
            if (!result) {
                break;
            }
        }
        return result;
    }

    @Override
    public Map<PublicIp, Set<Service>> getIpToServices(List<PublicIp> publicIps, boolean rulesRevoked, boolean includingFirewall) {
        Map<PublicIp, Set<Service>> ipToServices = new HashMap<>();

        if (publicIps != null && !publicIps.isEmpty()) {
            Set<Long> networkSNAT = new HashSet<>();
            for (PublicIp ip : publicIps) {
                Set<Service> services = ipToServices.get(ip);
                if (services == null) {
                    services = new HashSet<>();
                }
                if (ip.isSourceNat()) {
                    if (!networkSNAT.contains(ip.getAssociatedWithNetworkId())) {
                        services.add(Service.SourceNat);
                        networkSNAT.add(ip.getAssociatedWithNetworkId());
                    } else {
                        CloudRuntimeException ex = new CloudRuntimeException("Multiple generic source NAT IPs provided for network");
                        IPAddressVO ipAddr = ApiDBUtils.findIpAddressById(ip.getAssociatedWithNetworkId());
                        String ipAddrUuid = ip.getAssociatedWithNetworkId().toString();
                        if (ipAddr != null) {
                            ipAddrUuid = ipAddr.getUuid();
                        }
                        ex.addProxyObject(ipAddrUuid, "networkId");
                        throw ex;
                    }
                }
                ipToServices.put(ip, services);

                if (ip.getState() == State.Allocating) {
                    continue;
                }

                Set<Purpose> purposes = getPublicIpPurposeInRules(ip, false, includingFirewall);
                if (ip.isOneToOneNat() && ip.getAssociatedWithVmId() != null) {
                    if (purposes == null) {
                        purposes = new HashSet<>();
                    }
                    purposes.add(Purpose.StaticNat);
                }
                if (purposes == null || purposes.isEmpty()) {
                    purposes = getPublicIpPurposeInRules(ip, true, includingFirewall);
                    if (ip.isOneToOneNat()) {
                        if (purposes == null) {
                            purposes = new HashSet<>();
                        }
                        purposes.add(Purpose.StaticNat);
                    }
                    if (purposes == null || purposes.isEmpty()) {
                        continue;
                    } else {
                        if (rulesRevoked) {
                            ip.setState(State.Releasing);
                        } else {
                            if (ip.getState() == State.Releasing) {
                                ip.setState(State.Allocated);
                            }
                        }
                    }
                }
                if (purposes.contains(Purpose.StaticNat)) {
                    services.add(Service.StaticNat);
                }
                if (purposes.contains(Purpose.LoadBalancing)) {
                    services.add(Service.Lb);
                }
                if (purposes.contains(Purpose.PortForwarding)) {
                    services.add(Service.PortForwarding);
                }
                if (purposes.contains(Purpose.Vpn)) {
                    services.add(Service.Vpn);
                }
                if (purposes.contains(Purpose.Firewall)) {
                    services.add(Service.Firewall);
                }
                if (services.isEmpty()) {
                    continue;
                }
                ipToServices.put(ip, services);
            }
        }
        return ipToServices;
    }

    private Set<Purpose> getPublicIpPurposeInRules(PublicIp ip, boolean includeRevoked, boolean includingFirewall) {
        Set<Purpose> result = new HashSet<>();
        List<FirewallRuleVO> rules;
        if (includeRevoked) {
            rules = firewallRulesDao.listByIp(ip.getId());
        } else {
            rules = firewallRulesDao.listByIpAndNotRevoked(ip.getId());
        }

        if (rules == null || rules.isEmpty()) {
            return null;
        }

        for (FirewallRuleVO rule : rules) {
            if (rule.getPurpose() != Purpose.Firewall || includingFirewall) {
                result.add(rule.getPurpose());
            }
        }

        return result;
    }
}
