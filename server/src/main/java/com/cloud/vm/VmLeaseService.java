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
package com.cloud.vm;

import org.apache.cloudstack.vm.lease.VMLeaseManager;

import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.uservm.UserVm;

/**
 * VM instance lease management — validating user-supplied lease properties
 * and writing the lease expiry/action/state details for a VM instance.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 6). {@code UserVmManagerImpl}
 * keeps delegating wrappers so the {@link UserVmManager} interface
 * contract and existing test spies continue to work.
 */
public interface VmLeaseService {

    /**
     * Sanity-check the {@code leaseduration} / {@code leaseexpiryaction}
     * pair from an API request. Both null (or duration == -1) means "no
     * lease" — accepted. When either is supplied, both must be valid.
     *
     * @throws com.cloud.exception.InvalidParameterValueException on a
     *         negative/zero duration, a duration above
     *         {@link VMLeaseManager#MAX_LEASE_DURATION_DAYS}, or a missing
     *         expiry action when a duration is given.
     */
    void validateLeaseProperties(Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction);

    /**
     * Apply a lease while creating a new VM. Falls back to the service
     * offering's defaults when the caller didn't supply explicit values.
     */
    void applyLeaseOnCreateInstance(UserVm vm,
                                    Integer leaseDuration,
                                    VMLeaseManager.ExpiryAction leaseExpiryAction,
                                    ServiceOfferingJoinVO serviceOfferingJoinVO);

    /**
     * Modify the lease on an existing VM. Enforces that the instance was
     * originally deployed with a lease and that the existing lease is
     * neither already expired nor in a non-{@code PENDING} state.
     */
    void applyLeaseOnUpdateInstance(UserVm instance,
                                    Integer leaseDuration,
                                    VMLeaseManager.ExpiryAction leaseExpiryAction);

    /**
     * Write the lease expiry/action/state detail rows for {@code vm}.
     * Idempotent: no-op when {@code vm} or {@code leaseDuration} is
     * absent or non-positive.
     */
    void addLeaseDetailsForInstance(UserVm vm, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction);
}
