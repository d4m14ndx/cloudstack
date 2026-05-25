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
package com.cloud.api;

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.IPAddressResponse;
import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.NicSecondaryIpResponse;
import org.apache.cloudstack.api.response.VlanIpRangeResponse;

import com.cloud.dc.Vlan;
import com.cloud.network.IpAddress;
import com.cloud.network.PublicIpQuarantine;
import com.cloud.vm.NicSecondaryIp;

public interface ApiAddressVlanResponseService {

    VlanIpRangeResponse createVlanIpRangeResponse(Vlan vlan);

    VlanIpRangeResponse createVlanIpRangeResponse(Class<? extends VlanIpRangeResponse> subClass, Vlan vlan);

    IPAddressResponse createIPAddressResponse(ResponseView view, IpAddress ipAddr);

    NicSecondaryIpResponse createSecondaryIPToNicResponse(NicSecondaryIp result);

    IpQuarantineResponse createQuarantinedIpsResponse(PublicIpQuarantine quarantinedIp);

    /**
     * Set the NicSecondaryIpResponse object with the IP address that is not null (IPv4 or IPv6).
     */
    static void setResponseIpAddress(NicSecondaryIp result, NicSecondaryIpResponse response) {
        if (result.getIp4Address() != null) {
            response.setIpAddr(result.getIp4Address());
        } else if (result.getIp6Address() != null) {
            response.setIpAddr(result.getIp6Address());
        }
    }
}
