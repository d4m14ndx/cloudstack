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
package com.cloud.network.as;

import java.util.List;

import org.apache.cloudstack.api.command.user.autoscale.CreateConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListConditionsCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateConditionCmd;

import com.cloud.exception.ResourceInUseException;

/**
 * AutoScale {@link Condition} CRUD — extracted from
 * {@link AutoScaleManagerImpl} as part of the Phase 4 Spring-component
 * decomposition.
 *
 * <p>This component owns the pure condition logic (validate operator
 * and threshold, resolve counter, persist; paginated ACL-aware list with
 * policy-filter join; referential-integrity check against active
 * {@link AutoScalePolicy}s before delete and against non-disabled
 * {@link AutoScaleVmGroup}s before update). {@code AutoScaleManagerImpl}
 * keeps its public-API methods as thin delegating wrappers so the
 * {@link AutoScaleService} contract and existing test spies continue to
 * work.
 */
public interface ConditionService {

    /**
     * Create a new condition from the {@code createCondition} API command.
     * Validates that the {@link Condition.Operator} value exists, that
     * the threshold is non-negative, and that the referenced counter
     * exists; persists with the resolved owner account.
     */
    Condition createCondition(CreateConditionCmd cmd);

    /**
     * Paginated, ACL-aware list of conditions for the
     * {@code listConditions} API. When a {@code policyId} is supplied
     * the result is joined against
     * {@link com.cloud.network.as.dao.AutoScalePolicyConditionMapDao}
     * to return only conditions referenced by that policy.
     */
    List<? extends Condition> listConditions(ListConditionsCmd cmd);

    /**
     * Delete a condition by id. Refuses to delete a condition that is
     * referenced by any persisted {@link AutoScalePolicy}; in that case
     * a {@link ResourceInUseException} is raised.
     */
    boolean deleteCondition(long conditionId) throws ResourceInUseException;

    /**
     * Update a condition's operator and threshold. Validates inputs and
     * refuses the update if the condition is being used by any
     * {@link AutoScaleVmGroup} that is not in the
     * {@link AutoScaleVmGroup.State#DISABLED} state. On a successful
     * update, statistics tied to the referencing policies are marked
     * inactive so the scaling loop re-evaluates with fresh data.
     */
    Condition updateCondition(UpdateConditionCmd cmd) throws ResourceInUseException;
}
