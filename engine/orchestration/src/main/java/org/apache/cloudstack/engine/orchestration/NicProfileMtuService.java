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
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.utils.Pair;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;

/**
 * Resolves and applies MTU values to virtual-router NICs and NIC profiles
 * based on the parent network and (optionally) the VPC that owns it.
 *
 * <p>Extracted from {@link NetworkOrchestrator} as part of the Phase 4
 * Spring-component decomposition. All extracted methods were package-private
 * helpers with no direct test coverage in {@code NetworkOrchestratorTest};
 * the orchestrator now delegates to this service at the original call sites
 * inside {@code allocateNic}, {@code prepareNic}, and {@code importNic}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Look up the guest (or, falling back, public) network and VPC for a
 *       given virtual-router VM id.</li>
 *   <li>Apply the resolved MTU to a persistence-level {@link NicVO}.</li>
 *   <li>Apply the resolved MTU to a runtime {@link NicProfile}.</li>
 * </ul>
 */
public interface NicProfileMtuService {

    /**
     * Resolve the guest network (preferred) or public network and the owning
     * VPC, if any, for the router VM with the supplied id.
     *
     * <p>The lookup first asks for a {@code Guest} traffic-type router row; if
     * none exists it falls back to {@code Public}. When both fall through, the
     * method returns {@code null}.
     *
     * @param routerId id of the domain router VM
     * @return a pair of (network, vpc) -- {@code vpc} is {@code null} when the
     *         router is not bound to a VPC; the whole result is {@code null}
     *         when no router row is found
     */
    Pair<NetworkVO, VpcVO> getGuestNetworkRouterAndVpcDetails(long routerId);

    /**
     * Apply the appropriate MTU to a virtual-router {@link NicVO} based on
     * the NIC's traffic type:
     * <ul>
     *   <li>{@code Public} -- uses {@code VpcVO.getPublicMtu()} when the
     *       router is VPC-attached, otherwise {@code NetworkVO.getPublicMtu()};
     *       does nothing when {@code networks} is {@code null}.</li>
     *   <li>{@code Guest} -- uses {@code network.getPrivateMtu()}.</li>
     *   <li>Any other traffic type -- no-op.</li>
     * </ul>
     *
     * @param networks the (guest/public network, optional VPC) pair as returned
     *                 by {@link #getGuestNetworkRouterAndVpcDetails(long)};
     *                 may be {@code null}
     * @param network  the network the NIC belongs to (drives the traffic-type
     *                 branch)
     * @param vo       the NIC VO to mutate
     */
    void setMtuDetailsInVRNic(Pair<NetworkVO, VpcVO> networks, Network network, NicVO vo);

    /**
     * Apply the appropriate MTU to a runtime {@link NicProfile} based on the
     * supplied traffic type:
     * <ul>
     *   <li>{@code Public} -- uses {@code VpcVO.getPublicMtu()} when the
     *       router is VPC-attached, otherwise {@code NetworkVO.getPublicMtu()}.</li>
     *   <li>{@code Guest} -- uses {@code NetworkVO.getPrivateMtu()}.</li>
     *   <li>Any other traffic type -- no-op.</li>
     * </ul>
     *
     * <p>No-op when {@code networks} or the first element of the pair is
     * {@code null}.
     *
     * @param networks    the (guest/public network, optional VPC) pair as
     *                    returned by
     *                    {@link #getGuestNetworkRouterAndVpcDetails(long)};
     *                    may be {@code null}
     * @param trafficType the traffic type that drives the MTU choice
     * @param vmNic       the NIC profile to mutate
     */
    void setMtuInVRNicProfile(Pair<NetworkVO, VpcVO> networks, TrafficType trafficType, NicProfile vmNic);
}
