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
import org.apache.cloudstack.api.response.PrivateGatewayResponse;
import org.apache.cloudstack.api.response.RemoteAccessVpnResponse;
import org.apache.cloudstack.api.response.Site2SiteCustomerGatewayResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnConnectionResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnGatewayResponse;
import org.apache.cloudstack.api.response.StaticRouteResponse;
import org.apache.cloudstack.api.response.VpcOfferingResponse;
import org.apache.cloudstack.api.response.VpcResponse;
import org.apache.cloudstack.api.response.VpnUsersResponse;

import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteCustomerGateway;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.Site2SiteVpnGateway;
import com.cloud.network.VpnUser;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcOffering;

public interface ApiVpcVpnResponseService {

    VpcOfferingResponse createVpcOfferingResponse(VpcOffering offering);

    VpcResponse createVpcResponse(ResponseView view, Vpc vpc);

    PrivateGatewayResponse createPrivateGatewayResponse(ResponseView view, PrivateGateway result);

    StaticRouteResponse createStaticRouteResponse(StaticRoute result);

    Site2SiteVpnGatewayResponse createSite2SiteVpnGatewayResponse(Site2SiteVpnGateway result);

    Site2SiteCustomerGatewayResponse createSite2SiteCustomerGatewayResponse(Site2SiteCustomerGateway result);

    Site2SiteVpnConnectionResponse createSite2SiteVpnConnectionResponse(Site2SiteVpnConnection result);

    VpnUsersResponse createVpnUserResponse(VpnUser vpnUser);

    RemoteAccessVpnResponse createRemoteAccessVpnResponse(RemoteAccessVpn vpn);
}
