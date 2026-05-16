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
package org.apache.cloudstack.vm;

import org.apache.cloudstack.api.ServerApiException;

import com.cloud.dc.DataCenter;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;

/**
 * Pre-flight validation helpers for the NIC / network side of the
 * unmanaged-VM import flow — verifies that the discovered hypervisor
 * NIC is compatible with the CloudStack network it is being mapped
 * onto (zone, permission, VLAN/PVLAN match for VMware, hostname
 * uniqueness, IP availability, and MAC-address collision).
 *
 * <p>Extracted from {@link UnmanagedVMsManagerImpl} as part of the
 * Phase 4 Spring-component decomposition. {@code UnmanagedVMsManagerImpl}
 * keeps delegating wrappers around each of these calls so existing
 * orchestration paths and any test spies on the manager continue to
 * work unchanged.
 */
public interface UnmanagedInstanceNicValidator {

    /**
     * Reject when either {@code nic} or {@code network} is {@code null}.
     * Used as a shared prelude by the other NIC checks.
     */
    void basicNetworkChecks(String instanceName, UnmanagedInstanceTO.Nic nic, Network network);

    /**
     * Verify that, for VMware-hosted unmanaged instances, the VLAN /
     * PVLAN advertised by the source NIC matches the broadcast URI of
     * the target CloudStack network.
     */
    void checksOnlyNeededForVmware(UnmanagedInstanceTO.Nic nic, Network network, Hypervisor.HypervisorType hypervisorType);

    /**
     * Verify the target network exists, lives in the same zone as the
     * import, that the owner has permission to use it, and (for VMware,
     * non-auto-assigned isolated networks aside) that the VLAN / PVLAN
     * tagging matches.
     */
    void checkUnmanagedNicAndNetworkForImport(String instanceName, UnmanagedInstanceTO.Nic nic, Network network,
                                              DataCenter zone, Account owner, boolean autoAssign,
                                              Hypervisor.HypervisorType hypervisorType) throws ServerApiException;

    /**
     * Reject the import when another VM in the same network already
     * uses the requested hostname.
     */
    void checkUnmanagedNicAndNetworkHostnameForImport(String instanceName, UnmanagedInstanceTO.Nic nic, Network network,
                                                     String hostName) throws ServerApiException;

    /**
     * Verify a usable IPv4 address is supplied for non-L2 networks, and
     * that — if not the {@code auto} sentinel — the address is actually
     * available for assignment in the target network.
     */
    void checkUnmanagedNicIpAndNetworkForImport(String instanceName, UnmanagedInstanceTO.Nic nic, Network network,
                                                Network.IpAddresses ipAddresses) throws ServerApiException;

    /**
     * Reject when a NIC with the same MAC address already exists on the
     * target network and the caller has not requested a regenerate via
     * the {@code forced} flag.
     */
    void checkUnmanagedNicAndNetworkMacAddressForImport(NetworkVO network, UnmanagedInstanceTO.Nic nic, boolean forced);
}
