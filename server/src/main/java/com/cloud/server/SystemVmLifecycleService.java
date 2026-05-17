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
package com.cloud.server;

import org.apache.cloudstack.api.command.admin.systemvm.DestroySystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.RebootSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StopSystemVmCmd;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

/**
 * System VM lifecycle dispatcher — type-aware start, stop, reboot, and
 * destroy operations that cover both {@code ConsoleProxy} and
 * {@code SecondaryStorageVm} instances under a single set of
 * {@code ManagementService} entry points.
 *
 * <p>Each public method here looks up the {@link VMInstanceVO} by id, refuses
 * anything that is not a {@link VirtualMachine.Type#ConsoleProxy} or
 * {@link VirtualMachine.Type#SecondaryStorageVm}, emits a nested
 * {@link com.cloud.event.ActionEventUtils action event} reflecting the
 * specific system-VM flavour, and then hands off to the corresponding type
 * manager: {@link com.cloud.consoleproxy.ConsoleProxyManager} for CPVMs or
 * {@link com.cloud.storage.secondary.SecondaryStorageVmManager} for SSVMs.
 * SSVM destroy additionally clears any volume and template extract URLs
 * recorded for the zone so a fresh SSVM can repopulate them.
 *
 * <p>This service does not own the console-access lookups (those live on
 * {@link ConsoleAccessService}, extracted previously), the scale or upgrade
 * paths for system VMs, or the patch / control-IP plumbing. Those remain on
 * the god class. The five public {@code ManagementService} entry points
 * covered here ({@code startSystemVM}, {@code stopSystemVM},
 * {@code rebootSystemVM}, {@code destroySystemVM},
 * {@code findSystemVMTypeById}) remain on the god class as one-line
 * delegating wrappers so the {@code @ActionEvent} annotations the API
 * dispatcher reads via reflection stay on the {@link ManagementServerImpl}
 * methods, and any callers still binding the {@code ManagementService}
 * interface continue to resolve their method calls there.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition.
 */
public interface SystemVmLifecycleService {

    /**
     * Return the {@link VirtualMachine.Type} for the system VM identified by
     * {@code instanceId}, restricted to {@link VirtualMachine.Type#ConsoleProxy}
     * or {@link VirtualMachine.Type#SecondaryStorageVm}. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} if no
     * such system VM exists.
     */
    VirtualMachine.Type findSystemVMTypeById(long instanceId);

    /**
     * Start the system VM identified by {@code vmId}. Routes to
     * {@link com.cloud.consoleproxy.ConsoleProxyManager#startProxy} for
     * console-proxy VMs and to
     * {@link com.cloud.storage.secondary.SecondaryStorageVmManager#startSecStorageVm}
     * for secondary-storage VMs. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * the id does not match a system VM of either type.
     */
    VirtualMachine startSystemVM(long vmId);

    /**
     * Stop the system VM described by {@code cmd}. The {@code forced} flag is
     * propagated to {@link com.cloud.vm.VirtualMachineManager#advanceStop} so
     * an unresponsive system VM can be powered off without a clean shutdown.
     * Throws {@link com.cloud.exception.InvalidParameterValueException}
     * if the id is unknown,
     * {@link com.cloud.exception.ResourceUnavailableException} /
     * {@link com.cloud.exception.ConcurrentOperationException} if the stop
     * cannot be coordinated, and wraps
     * {@link com.cloud.exception.OperationTimedoutException} as a
     * {@link com.cloud.utils.exception.CloudRuntimeException} so callers do
     * not have to handle the agent-timeout case explicitly.
     */
    VMInstanceVO stopSystemVM(StopSystemVmCmd cmd)
            throws ResourceUnavailableException, ConcurrentOperationException;

    /**
     * Reboot the system VM described by {@code cmd}. A non-forced reboot
     * defers to {@link com.cloud.consoleproxy.ConsoleProxyManager#rebootProxy}
     * or
     * {@link com.cloud.storage.secondary.SecondaryStorageVmManager#rebootSecStorageVm};
     * a forced reboot does an {@code advanceStop} followed by a fresh start
     * via the corresponding manager. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * the id does not match a system VM. Wraps
     * {@link com.cloud.exception.ResourceUnavailableException} and
     * {@link com.cloud.exception.OperationTimedoutException} as
     * {@link com.cloud.utils.exception.CloudRuntimeException}s so the caller
     * sees a single failure mode.
     */
    VMInstanceVO rebootSystemVM(RebootSystemVmCmd cmd);

    /**
     * Destroy the system VM described by {@code cmd}. Console-proxy destroy
     * defers to {@link com.cloud.consoleproxy.ConsoleProxyManager#destroyProxy};
     * secondary-storage destroy additionally clears any volume and template
     * extract URLs recorded for the SSVM's data centre so the next SSVM does
     * not serve stale download links. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * the id does not match a system VM.
     */
    VMInstanceVO destroySystemVM(DestroySystemVmCmd cmd);
}
