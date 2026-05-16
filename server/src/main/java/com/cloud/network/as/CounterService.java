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

import org.apache.cloudstack.api.command.admin.autoscale.CreateCounterCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListCountersCmd;

import com.cloud.exception.ResourceInUseException;

/**
 * AutoScale {@link Counter} CRUD — extracted from
 * {@link AutoScaleManagerImpl} as part of the Phase 4 Spring-component
 * decomposition.
 *
 * <p>This component owns the pure counter logic (validate source and
 * provider, reject duplicates, look up by id, paginated list,
 * referential-integrity check against {@link Condition} before delete).
 * {@code AutoScaleManagerImpl} keeps its public-API methods as thin
 * delegating wrappers so the {@link AutoScaleService} contract and
 * existing test spies continue to work.
 */
public interface CounterService {

    /**
     * Create a new counter from the {@code createCounter} API command.
     * Validates that the {@link Counter.Source} and
     * {@link com.cloud.network.Network.Provider} values exist and that
     * no counter with the same name, value and provider is already
     * persisted.
     */
    Counter createCounter(CreateCounterCmd cmd);

    /** Look up a single counter by id; returns {@code null} when not found. */
    Counter getCounter(long counterId);

    /**
     * Paginated, provider-filtered list of counters for the
     * {@code listCounters} API. The provider string is normalized via
     * {@link com.cloud.network.Network.Provider#getProvider(String)} and
     * unknown providers raise
     * {@link com.cloud.exception.InvalidParameterValueException}.
     */
    List<? extends Counter> listCounters(ListCountersCmd cmd);

    /**
     * Delete a counter by id. Refuses to delete a counter that is
     * referenced by any persisted {@link Condition}; in that case a
     * {@link ResourceInUseException} is raised.
     */
    boolean deleteCounter(long counterId) throws ResourceInUseException;
}
