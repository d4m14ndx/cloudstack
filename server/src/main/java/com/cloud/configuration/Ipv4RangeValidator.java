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
package com.cloud.configuration;

import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.net.NetUtils;

final class Ipv4RangeValidator {
    private Ipv4RangeValidator() {
    }

    static void requireValidIp(final String ip, final String message) {
        if (!NetUtils.isValidIp4(ip)) {
            throw new InvalidParameterValueException(message);
        }
    }

    static void requireValidIpIfPresent(final String ip, final String message) {
        if (StringUtils.isNotEmpty(ip) && !NetUtils.isValidIp4(ip)) {
            throw new InvalidParameterValueException(message);
        }
    }

    static void requireValidNetmask(final String netmask, final String message) {
        if (!NetUtils.isValidIp4Netmask(netmask)) {
            throw new InvalidParameterValueException(message);
        }
    }

    static void requireStartBeforeEnd(final String startIp, final String endIp, final String message) {
        if (NetUtils.ip2Long(startIp) > NetUtils.ip2Long(endIp)) {
            throw new InvalidParameterValueException(message);
        }
    }

    static void validateOptionalRangeWithinCidr(final String startIp, final String endIp, final String cidrAddress, final long cidrSize) {
        requireValidIpIfPresent(startIp, "The start address of the IP range is not a valid IP address.");
        requireValidIpIfPresent(endIp, "The end address of the IP range is not a valid IP address.");

        if (StringUtils.isNotEmpty(startIp) && !NetUtils.getCidrSubNet(startIp, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The start address of the IP range is not in the CIDR subnet.");
        }

        if (StringUtils.isNotEmpty(endIp) && !NetUtils.getCidrSubNet(endIp, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The end address of the IP range is not in the CIDR subnet.");
        }

        if (StringUtils.isNotEmpty(endIp)) {
            requireStartBeforeEnd(startIp, endIp, "The start IP address must have a lower value than the end IP address.");
        }
    }
}
