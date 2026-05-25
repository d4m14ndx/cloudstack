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

/**
 * Emits dedication/planner alerts for VM host migration.
 *
 * <p>This service is intentionally alert-only. Migration orchestration,
 * destination validation, storage access groups, and strict host tag checks
 * stay with their existing owners.</p>
 */
public interface VmMigrationDedicationService {
    void checkHostsDedication(VMInstanceVO vm, long srcHostId, long destHostId);
}
