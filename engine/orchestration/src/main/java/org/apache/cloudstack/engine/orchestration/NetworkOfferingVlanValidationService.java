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

import java.net.URI;

import com.cloud.dc.DataCenterVO;
import com.cloud.network.PhysicalNetwork;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.user.Account;

public interface NetworkOfferingVlanValidationService {

    boolean isSharedNetworkWithoutSpecifyVlan(NetworkOffering offering);

    boolean isPrivateGatewayWithoutSpecifyVlan(NetworkOffering offering);

    URI encodeVlanIdIntoBroadcastUri(String vlanId, PhysicalNetwork physicalNetwork);

    void validateGuestNetworkOfferingVlan(String vlanId, String isolatedPvlan, boolean bypassVlanOverlapCheck,
            NetworkOfferingVO offering, PhysicalNetwork physicalNetwork, DataCenterVO zone, long zoneId, Account owner, boolean isPrivateNetwork);

    void checkL2OfferingServices(NetworkOfferingVO offering);
}
