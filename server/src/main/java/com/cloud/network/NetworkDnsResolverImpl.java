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

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.Pair;
import com.cloud.utils.net.NetUtils;

/**
 * DNS resolution and validation helpers extracted from
 * {@link NetworkModelImpl}.
 *
 * @see NetworkDnsResolver
 */
@Component
public class NetworkDnsResolverImpl implements NetworkDnsResolver {

    @Inject
    VpcDao vpcDao;

    @Override
    public Pair<String, String> getNetworkIp4Dns(final Network network, final DataCenter zone) {
        if (StringUtils.isNotBlank(network.getDns1())) {
            return new Pair<>(network.getDns1(), network.getDns2());
        }
        if (network.getVpcId() != null) {
            Vpc vpc = vpcDao.findById(network.getVpcId());
            if (vpc != null && StringUtils.isNotBlank(vpc.getIp4Dns1())) {
                return new Pair<>(vpc.getIp4Dns1(), vpc.getIp4Dns2());
            }
        }
        return new Pair<>(zone.getDns1(), zone.getDns2());
    }

    @Override
    public Pair<String, String> getNetworkIp6Dns(final Network network, final DataCenter zone) {
        if (StringUtils.isNotBlank(network.getIp6Dns1())) {
            return new Pair<>(network.getIp6Dns1(), network.getIp6Dns2());
        }
        if (network.getVpcId() != null) {
            Vpc vpc = vpcDao.findById(network.getVpcId());
            if (vpc != null && StringUtils.isNotBlank(vpc.getIp6Dns1())) {
                return new Pair<>(vpc.getIp6Dns1(), vpc.getIp6Dns2());
            }
        }
        return new Pair<>(zone.getIp6Dns1(), zone.getIp6Dns2());
    }

    @Override
    public void verifyIp4DnsPair(String ip4Dns1, String ip4Dns2) {
        if (StringUtils.isEmpty(ip4Dns1) && StringUtils.isNotEmpty(ip4Dns2)) {
            throw new InvalidParameterValueException("Second IPv4 DNS can be specified only with the first IPv4 DNS");
        }
        if (StringUtils.isNotEmpty(ip4Dns1) && !NetUtils.isValidIp4(ip4Dns1)) {
            throw new InvalidParameterValueException("Invalid IPv4 for DNS1");
        }
        if (StringUtils.isNotEmpty(ip4Dns2) && !NetUtils.isValidIp4(ip4Dns2)) {
            throw new InvalidParameterValueException("Invalid IPv4 for DNS2");
        }
    }

    @Override
    public void verifyIp6DnsPair(String ip6Dns1, String ip6Dns2) {
        if (StringUtils.isEmpty(ip6Dns1) && StringUtils.isNotEmpty(ip6Dns2)) {
            throw new InvalidParameterValueException("Second IPv6 DNS can be specified only with the first IPv6 DNS");
        }
        if (StringUtils.isNotEmpty(ip6Dns1) && !NetUtils.isValidIp6(ip6Dns1)) {
            throw new InvalidParameterValueException("Invalid IPv6 for IPv6 DNS1");
        }
        if (StringUtils.isNotEmpty(ip6Dns2) && !NetUtils.isValidIp6(ip6Dns2)) {
            throw new InvalidParameterValueException("Invalid IPv6 for IPv6 DNS2");
        }
    }
}
