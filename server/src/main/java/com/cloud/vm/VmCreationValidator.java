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

import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;

import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;

/**
 * Deploy-time parameter validation helpers — sanity-checking the
 * service offering, template, and {@code details} map a caller supplies
 * to {@code createVirtualMachine}.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 10). {@code UserVmManagerImpl}
 * keeps delegating wrappers so the {@link UserVmManager} interface
 * contract is unchanged.
 */
public interface VmCreationValidator {

    /**
     * Reject when the service offering is inactive, when the caller
     * tries to override the disk offering of a strictly-mapped offering,
     * or when CPU/memory details are supplied for a non-dynamic offering.
     */
    void verifyServiceOffering(BaseDeployVMCmd cmd, ServiceOffering serviceOffering);

    /**
     * Reject VNF/non-VNF appliance mismatches and a few deploy-as-is
     * template invariants (root-disk size in the offering, rootdisksize
     * override, bootmode/boottype combinations).
     */
    void verifyTemplate(BaseDeployVMCmd cmd, VirtualMachineTemplate template, Long serviceOfferingId);

    /**
     * Validate the deploy-time {@code details} map: min/max IOPS pairs
     * must be sane, and {@code extraconfig} keys must not be smuggled in
     * via the regular details map.
     */
    void verifyDetails(Map<String, String> details);

    /**
     * Validate that {@code minIops} and {@code maxIops} are either both
     * supplied or both absent, are whole numbers, and satisfy
     * {@code minIops <= maxIops}.
     */
    void verifyMinAndMaxIops(String minIops, String maxIops);
}
