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
package com.cloud.storage;

import org.apache.cloudstack.api.command.admin.storage.CancelPrimaryStorageMaintenanceCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;

/**
 * Primary storage pool maintenance state transitions — drive a primary
 * storage pool (and, for vSphere datastore clusters, every child pool)
 * into {@code PrepareForMaintenance} / {@code Maintenance} and back out
 * to {@code Up}.
 *
 * <p>Extracted from {@link StorageManagerImpl} as part of the Phase 4
 * Spring-component decomposition. The two public {@code StorageService}
 * entry points stay on the manager — they carry the {@code @DB} and
 * {@code @ActionEvent} annotations the framework expects — and now
 * delegate the workflow to this service.
 */
public interface PrimaryStorageMaintenanceService {

    /**
     * Drive the supplied primary storage pool into maintenance mode.
     *
     * <p>For datastore-cluster pools this also flips every child pool
     * to {@code PrepareForMaintenance} (in a single DB transaction) and
     * then invokes {@link DataStoreLifeCycle#maintain} on each child;
     * any child failure rolls all children and the parent to
     * {@code ErrorInMaintenance} and throws a
     * {@link com.cloud.utils.exception.CloudRuntimeException}.
     *
     * @param primaryStorageId id of the pool to prepare for
     *     maintenance — must resolve via the primary storage DAO and
     *     be in {@code Up} or {@code ErrorInMaintenance} state.
     * @return the refreshed {@link PrimaryDataStoreInfo} view of the
     *     pool from the {@code DataStoreManager}.
     * @throws com.cloud.exception.InvalidParameterValueException when
     *     the pool cannot be found or is in a state that cannot
     *     transition to maintenance.
     * @throws com.cloud.utils.exception.CloudRuntimeException when a
     *     duplicate prepare-for-maintenance job is already running for
     *     a datastore cluster, or a child pool fails to enter
     *     maintenance.
     */
    PrimaryDataStoreInfo preparePrimaryStorageForMaintenance(Long primaryStorageId)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Cancel maintenance mode for the primary storage pool identified
     * by {@code cmd.getId()}, returning the pool to {@code Up}.
     *
     * <p>For datastore-cluster pools the parent is flipped to {@code Up}
     * first and then {@link DataStoreLifeCycle#cancelMaintain} is
     * called on every child pool before the parent's own
     * {@code cancelMaintain}. The pool must currently be in
     * {@code Maintenance} or {@code ErrorInMaintenance} —
     * {@code Up} / {@code PrepareForMaintenance} both raise
     * {@link StorageUnavailableException}.
     */
    PrimaryDataStoreInfo cancelPrimaryStorageForMaintenance(CancelPrimaryStorageMaintenanceCmd cmd)
            throws ResourceUnavailableException;
}
