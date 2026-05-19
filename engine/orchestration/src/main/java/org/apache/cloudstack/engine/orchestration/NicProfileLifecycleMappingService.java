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
import java.util.Map;

import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkProfile;
import com.cloud.network.dao.NetworkVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;

public interface NicProfileLifecycleMappingService {

    Integer applyProfileToNic(NicVO vo, NicProfile profile, Integer deviceId);

    void applyProfileToNicForRelease(NicVO vo, NicProfile profile);

    void applyProfileToNetwork(NetworkVO network, NetworkProfile profile);

    NicTO toNicTO(NicVO nic, NicProfile profile, NetworkVO config);

    NicProfile getNicProfileForVm(Network network, NicProfile requested, VirtualMachine vm);

    boolean getNicProfileDefaultNic(NicProfile nicProfile);

    List<NicProfile> getNicProfiles(Long vmId, HypervisorType hypervisorType);

    List<NicProfile> getNicProfiles(VirtualMachine vm);

    Map<String, String> getSystemVMAccessDetails(VirtualMachine vm);
}
