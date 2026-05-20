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

import java.util.List;
import java.util.function.BiConsumer;

import com.cloud.network.Network;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.vm.Nic;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Small NIC helper operations extracted from {@link NetworkOrchestrator}.
 */
public interface NicAuxiliaryService {

    List<? extends Nic> listVmNics(long vmId, Long nicId, Long networkId, String keyword, List<NetworkGuru> networkGurus);

    boolean isSecondaryIpSetForNic(long nicId);

    boolean removeVmSecondaryIpsOfNic(long nicId);

    NicVO savePlaceholderNic(Network network, String ip4Address, String ip6Address, Type vmType);

    NicVO savePlaceholderNic(Network network, String ip4Address, String ip6Address, String ip6Cidr, String ip6Gateway, String reserver, Type vmType);

    void unmanageNics(VirtualMachineProfile vm, BiConsumer<VirtualMachineProfile, NicVO> removeNic);

    void expungeLbVmRefs(List<NetworkElement> networkElements, List<Long> vmIds, Long batchSize);
}
