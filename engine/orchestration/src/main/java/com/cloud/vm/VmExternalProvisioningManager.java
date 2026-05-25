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

import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * External-hypervisor provisioning handshake and command-decoration helpers.
 *
 * <p>Handles the {@code PrepareExternalProvisioning} command/answer round-trip
 * for External hypervisor VMs (pre-start metadata exchange, NIC/detail updates),
 * and decorates {@link StartCommand}, {@link StopCommand}, and
 * {@link RebootCommand} with the per-host external-access details required by
 * the External hypervisor driver.
 *
 * <p>Extracted from {@link VirtualMachineManagerImpl} as part of the
 * Phase 4 Spring-component decomposition.
 */
public interface VmExternalProvisioningManager {

    /**
     * Set the {@code metadataManufacturer} and {@code metadataProductName}
     * fields on {@code vmTO} from the zone-scoped config keys, falling back
     * to global defaults when the zone-level value is blank.
     */
    void updateVmMetadataManufacturerAndProduct(VirtualMachineTO vmTO, VMInstanceVO vm);

    /**
     * Persist new VM details returned by a PrepareExternalProvisioning answer
     * back to {@code vmTO} and the {@link UserVmVO} detail table.  No-ops when
     * {@code newDetails} is {@code null} or equal to the details already on
     * {@code vmTO}.
     */
    void updateExternalVmDetailsFromPrepareAnswer(VirtualMachineTO vmTO, UserVmVO userVmVO,
            Map<String, String> newDetails);

    /**
     * Apply VNC-password and detail updates from {@code updatedTO} back into
     * {@code vmTO} and the underlying {@link UserVmVO} row.  No-ops when
     * neither field changed.
     */
    void updateExternalVmDataFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO);

    /**
     * Reconcile NIC MAC/IP addresses returned by a PrepareExternalProvisioning
     * answer against the live {@link com.cloud.vm.dao.NicDao} rows.  No-ops
     * when either NIC array is {@code null}.
     */
    void updateExternalVmNicsFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO);

    /**
     * Convenience wrapper: apply both data and NIC updates from a
     * PrepareExternalProvisioning answer.  No-ops when {@code updatedTO}
     * is {@code null}.
     */
    void updateExternalVmFromPrepareAnswer(VirtualMachineTO vmTO, VirtualMachineTO updatedTO);

    /**
     * Send a {@code PrepareExternalProvisioningCommand} to the host for the
     * first start of an External VM whose template's extension requires it,
     * then reconcile any updates returned in the answer.
     *
     * <p>The caller is responsible for pre-computing {@code vmTO} (via
     * {@code toVmTO(vmProfile)}) so that the {@code @Spy} stub in unit tests
     * for {@link VirtualMachineManagerImpl} is honoured correctly.  The NIC
     * array on {@code vmTO} will be populated from the database when it is
     * empty.
     *
     * @param firstStart {@code true} only on the very first start of the VM
     * @param host       the destination host; may be {@code null}
     * @param vmProfile  VM profile including NICs
     * @param dataCenter target zone (used to resolve NIC profiles)
     * @param vmTO       pre-built transfer object for the VM
     * @throws CloudRuntimeException when the agent call fails or returns an
     *         unexpected/negative answer
     */
    void processPrepareExternalProvisioning(boolean firstStart, Host host,
            VirtualMachineProfile vmProfile, DataCenter dataCenter, VirtualMachineTO vmTO)
            throws CloudRuntimeException;

    /**
     * Populate a {@link StartCommand} with the per-host external-access
     * details and the default-NIC VLAN segment name.  No-ops for non-External
     * hypervisors.
     */
    void updateStartCommandWithExternalDetails(Host host, VirtualMachineTO vmTO, StartCommand command);

    /**
     * Populate a {@link StopCommand} with the per-host external-access
     * details and a cleaned-up {@link VirtualMachineTO}.  No-ops for
     * non-External hypervisors or when the VM profile has no host id.
     *
     * <p>The caller is responsible for pre-computing {@code vmTO} (via
     * {@code ObjectUtils.defaultIfNull(stopCommand.getVirtualMachine(), toVmTO(vmProfile))})
     * so that the {@code @Spy} stub in unit tests is honoured correctly.
     *
     * @param hypervisorType the VM's hypervisor type
     * @param vmProfile      VM profile including the host id
     * @param stopCommand    command to decorate
     * @param vmTO           pre-built (or pre-resolved from command) transfer object
     */
    void updateStopCommandForExternalHypervisorType(HypervisorType hypervisorType,
            VirtualMachineProfile vmProfile, StopCommand stopCommand, VirtualMachineTO vmTO);

    /**
     * Populate a {@link RebootCommand} with the per-host external-access
     * details.  No-ops for non-External hypervisors.
     */
    void updateRebootCommandWithExternalDetails(Host host, VirtualMachineTO vmTO, RebootCommand rebootCmd);
}
