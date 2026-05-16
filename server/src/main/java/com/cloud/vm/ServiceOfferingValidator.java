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

import java.util.Map;

import com.cloud.service.ServiceOfferingVO;

/**
 * Validation rules for service offerings — bounds checking for max
 * resources, custom parameter consistency, and disk offering compatibility
 * during a service-offering change.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition. All instance methods on
 * {@code UserVmManagerImpl} that share these signatures continue to exist
 * as delegating wrappers so the {@link UserVmManager} contract and any
 * existing test spies keep working.
 */
public interface ServiceOfferingValidator {

    /**
     * Validates that a non-dynamic service offering's CPU and RAM are within
     * the global maximum bounds configured on the management server.
     *
     * @throws com.cloud.exception.InvalidParameterValueException when either
     *         dimension exceeds the configured maximum
     */
    void validateOfferingMaxResource(ServiceOfferingVO offering);

    /**
     * For dynamic (custom-sized) offerings, validates that the caller-supplied
     * cpuNumber / cpuSpeed / memory values are present and within the offering's
     * declared min/max bounds (plus the global maximums).
     */
    void validateCustomParameters(ServiceOfferingVO serviceOffering, Map<String, String> customParameters);

    /**
     * When swapping a VM's service offering, validates that the disk offering
     * strictness flag matches and (if strict) that the disk offering id matches.
     * Also delegates encryption compatibility check to the volume service.
     */
    void validateDiskOfferingChecks(ServiceOfferingVO currentServiceOffering, ServiceOfferingVO newServiceOffering);
}
