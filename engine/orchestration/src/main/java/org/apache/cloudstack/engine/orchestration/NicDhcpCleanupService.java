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

import com.cloud.network.Network;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Handles DHCP/DNS entry cleanup for NICs during the NIC removal lifecycle.
 *
 * <p>Extracted from {@link NetworkOrchestrator} as part of the Phase 4
 * Spring-component decomposition. The orchestrator continues to expose the
 * corresponding methods via one-line delegating wrappers so existing call
 * sites and test spies keep working unchanged.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Remove DHCP entries from network elements when a NIC is released.</li>
 *   <li>Determine whether a DHCP provider supports multiple subnets.</li>
 *   <li>Detect whether a NIC is the last one in its subnet.</li>
 *   <li>Remove DHCP service from a subnet when the last NIC departs.</li>
 * </ul>
 */
public interface NicDhcpCleanupService {

    /**
     * Remove the DHCP and DNS entry for the given NIC/VM profile from
     * all network elements that provide DHCP for the network.
     *
     * <p>Only removes entries for {@link com.cloud.vm.VirtualMachine.Type#User}
     * VMs. Silently logs and swallows {@link com.cloud.exception.ResourceUnavailableException}.
     *
     * @param network    the network the NIC belongs to
     * @param vmProfile  the VM profile being released
     * @param nicProfile the NIC profile whose DHCP/DNS entry is to be removed
     */
    void cleanupNicDhcpDnsEntry(Network network, VirtualMachineProfile vmProfile, NicProfile nicProfile);

    /**
     * Return {@code true} when the given {@link DhcpServiceProvider} advertises
     * support for the {@code DhcpAccrossMultipleSubnets} capability.
     *
     * @param dhcpServiceProvider the DHCP provider to inspect
     * @return {@code true} if multi-subnet DHCP is supported
     */
    boolean isDhcpAccrossMultipleSubnetsSupported(DhcpServiceProvider dhcpServiceProvider);

    /**
     * Return {@code true} when {@code nic} is the only {@link com.cloud.vm.VirtualMachine.Type#User}
     * NIC in its subnet (identified by network-id, IPv4 gateway, and broadcast URI).
     *
     * @param nic the NIC to check
     * @return {@code true} if no other user NIC shares the same subnet
     */
    boolean isLastNicInSubnet(NicVO nic);

    /**
     * Remove the DHCP service alias from the subnet that {@code nic} belongs
     * to, releasing the IP alias and un-assigning the alias address.
     *
     * <p>Executes inside a database transaction. Logs and swallows
     * {@link com.cloud.exception.ResourceUnavailableException} when the
     * virtual router is unreachable.
     *
     * @param nic the NIC whose subnet DHCP service should be removed
     */
    void removeDhcpServiceInSubnet(Nic nic);
}
