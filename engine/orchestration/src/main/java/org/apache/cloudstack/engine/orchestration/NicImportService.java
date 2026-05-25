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

import com.cloud.dc.DataCenter;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.network.Network;
import com.cloud.utils.Pair;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine;

/**
 * Handles import-NIC operations — extracted from {@link NetworkOrchestrator}.
 *
 * @see NicImportServiceImpl
 */
public interface NicImportService {

    Pair<NicProfile, Integer> importNic(String macAddress, int deviceId, Network network,
            Boolean isDefaultNic, VirtualMachine vm, Network.IpAddresses ipAddresses,
            DataCenter dataCenter, boolean forced)
            throws ConcurrentOperationException,
                   InsufficientVirtualNetworkCapacityException,
                   InsufficientAddressCapacityException;

    String getSelectedIpForNicImport(Network network, DataCenter dataCenter,
            Network.IpAddresses ipAddresses);

    String getSelectedIpForNicImportOnSharedNetwork(String requestedIp, Network network,
            DataCenter dataCenter);

    Pair<String, String> getNetworkGatewayAndNetmaskForNicImport(Network network,
            DataCenter dataCenter, String selectedIp);
}
