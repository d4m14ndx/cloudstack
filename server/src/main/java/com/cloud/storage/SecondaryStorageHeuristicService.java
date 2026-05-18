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

import org.apache.cloudstack.api.command.admin.storage.heuristics.CreateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.RemoveSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.UpdateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.secstorage.heuristics.Heuristic;

/**
 * Secondary-storage heuristic rule lifecycle — extracted from
 * {@link StorageManagerImpl}. Owns the four CRUD-ish entry points for
 * secondary-storage selector heuristics (zone-scoped JS rules that pick
 * which secondary store to use for a given workload).
 *
 * <ul>
 *   <li><b>Create</b> — validate the {@link
 *       org.apache.cloudstack.secstorage.heuristics.HeuristicType}, enforce
 *       one-rule-per-zone-per-type uniqueness, validate the JS rule, and
 *       persist a new {@code HeuristicVO}.</li>
 *   <li><b>Update</b> — replace the JS rule of an existing heuristic after
 *       validating it.</li>
 *   <li><b>Remove</b> — delete a heuristic by id, failing if it does not
 *       exist.</li>
 * </ul>
 *
 * <p>Rule validation rejects blank rules and asks the
 * {@code JsInterpreterHelper} to confirm the interpreter is enabled.
 */
public interface SecondaryStorageHeuristicService {

    Heuristic createSecondaryStorageHeuristic(CreateSecondaryStorageSelectorCmd cmd);

    Heuristic updateSecondaryStorageHeuristic(UpdateSecondaryStorageSelectorCmd cmd);

    void removeSecondaryStorageHeuristic(RemoveSecondaryStorageSelectorCmd cmd);
}
