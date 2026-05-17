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
package com.cloud.network.vpc;

import java.util.List;

import org.apache.cloudstack.api.command.user.vpc.CreatePrivateGatewayCmd;
import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.utils.Pair;

/**
 * VPC private-gateway CRUD, validation and provider application —
 * extracted from {@link VpcManagerImpl} as part of the Phase 4
 * Spring-component decomposition.
 *
 * <p>This component owns the lifecycle of {@link PrivateGateway} entities
 * attached to a {@link Vpc}: create (with network-offering, ACL,
 * physical-network and associated-network validation), apply against the
 * VPC's {@link com.cloud.network.element.VpcProvider}s, list, look up
 * by id, and delete (orchestrating route cleanup and provider tear-down).
 * {@code VpcManagerImpl} keeps its public-API methods as thin delegating
 * wrappers so the {@link VpcService} and {@link VpcManager} contracts
 * — including {@code applyVpcPrivateGateway} invoked by
 * {@link CreatePrivateGatewayCmd} — continue to work unchanged.
 */
public interface PrivateGatewayService {

    /** All private gateways on the given VPC, or {@code null} when none exist. */
    List<PrivateGateway> getVpcPrivateGateways(long vpcId);

    /** Look up a single private gateway by id, returning {@code null} when not found or not Private. */
    PrivateGateway getVpcPrivateGateway(long id);

    /**
     * Create a VPC private gateway from a user/admin
     * {@link CreatePrivateGatewayCmd}. Validates the VPC, network
     * offering, associated/physical network and ACL, creates (or reuses)
     * the underlying private network, persists the gateway row and
     * returns a freshly-loaded {@link PrivateGateway}.
     */
    PrivateGateway createVpcPrivateGateway(CreatePrivateGatewayCmd command) throws ResourceAllocationException,
            ConcurrentOperationException, InsufficientCapacityException;

    /**
     * Push the gateway to every supporting {@link com.cloud.network.element.VpcProvider}
     * and flip its state to {@code Ready} on success. When all providers
     * fail and {@code destroyOnFailure} is true the gateway is removed
     * from the DB.
     */
    PrivateGateway applyVpcPrivateGateway(long gatewayId, boolean destroyOnFailure) throws ConcurrentOperationException, ResourceUnavailableException;

    /**
     * Delete a VPC private gateway: refuses when static routes still
     * reference the gateway, tears it down on every provider, cleans up
     * residual routes and removes the persisted network/gateway rows.
     */
    boolean deleteVpcPrivateGateway(long gatewayId) throws ConcurrentOperationException, ResourceUnavailableException;

    /** Paginated, ACL-filtered list of private gateways for the listPrivateGateways API. */
    Pair<List<PrivateGateway>, Integer> listPrivateGateway(ListPrivateGatewaysCmd cmd);

    /**
     * Throws {@link com.cloud.exception.InvalidParameterValueException}
     * when {@code aclId} is non-null and either does not exist or
     * belongs to a different VPC (and is not a default ACL).
     */
    void validateVpcPrivateGatewayAclId(long vpcId, Long aclId);

    /**
     * Resolves the {@link NetworkOfferingVO} for a new private gateway:
     * the caller-supplied offering (validated to be a Guest/Isolated
     * offering) when present, otherwise the platform system offering
     * appropriate for whether a VLAN broadcast URI was specified.
     */
    NetworkOfferingVO getVpcPrivateGatewayNetworkOffering(Long networkOfferingIdPassed, String broadcastUri);

    /**
     * Resolves and validates the physical-network id for a new private
     * gateway, deriving it from the associated network or the network
     * offering tags when not explicitly supplied.
     */
    Long validateVpcPrivateGatewayPhysicalNetworkId(Long dcId, Long physicalNetworkId, Long associatedNetworkId, NetworkOfferingVO ntwkOff);
}
