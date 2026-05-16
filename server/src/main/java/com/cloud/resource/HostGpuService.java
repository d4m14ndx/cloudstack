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
package com.cloud.resource;

import java.util.HashMap;
import java.util.List;

import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.gpu.HostGpuGroupsVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.vm.VirtualMachine;

/**
 * Host-level GPU availability, allocation, and statistics — the per-host slice
 * of GPU management that lives next to {@link ResourceManagerImpl} (whereas
 * the cluster-wide {@link org.apache.cloudstack.gpu.GpuService} owns card,
 * device, and vGPU profile CRUD).
 *
 * <p>Operations covered:
 * <ul>
 *   <li>checking whether a host has any GPU groups at all
 *       ({@link #isHostGpuEnabled})</li>
 *   <li>listing available {@link HostGpuGroupsVO} rows for a host filtered by
 *       group name and vGPU type ({@link #listAvailableGPUDevice})</li>
 *   <li>capacity checks for an offering, host, and vm
 *       ({@link #isGPUDeviceAvailable})</li>
 *   <li>resolving an allocatable {@link GPUDeviceTO} for a VM start
 *       ({@link #getGPUDevice})</li>
 *   <li>persisting GPU group capacity changes after VM start / stop
 *       ({@link #updateGPUDetails}, {@link #updateGPUDetailsForVmStart},
 *       {@link #updateGPUDetailsForVmStop})</li>
 *   <li>pulling live GPU stats from the agent on a host
 *       ({@link #getGPUStatistics})</li>
 * </ul>
 *
 * <p>Extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code ResourceManagerImpl} keeps
 * one-line delegating wrappers so the {@link ResourceManager} interface
 * contract (which all of these methods are part of) and existing spy /
 * mock-based tests continue to work unchanged.
 */
public interface HostGpuService {

    /**
     * Check if the host has any GPU groups registered.
     *
     * @return {@code true} if the host has at least one GPU group entry.
     */
    boolean isHostGpuEnabled(long hostId);

    /**
     * List GPU groups on the host that have remaining capacity for the
     * requested {@code groupName} and {@code vgpuType}, ordered by
     * remaining capacity descending.
     */
    List<HostGpuGroupsVO> listAvailableGPUDevice(long hostId, String groupName, String vgpuType);

    /**
     * Check whether the host has GPU capacity matching the offering — either
     * via the modern {@link VgpuProfileVO} (offering carries a vgpuProfileId)
     * or via the legacy service-offering-details path (vgpuType + pciDevice
     * details).
     */
    boolean isGPUDeviceAvailable(ServiceOffering offering, Host host, Long vmId);

    /**
     * Check whether the host has GPU capacity for the given vGPU profile
     * and count. For XenServer hosts this falls back to the legacy
     * group-name / vGPU-type capacity table; for other hypervisors it
     * delegates to the central {@link org.apache.cloudstack.gpu.GpuService}.
     */
    boolean isGPUDeviceAvailable(Host host, Long vmId, VgpuProfileVO vgpuProfile, int gpuCount);

    /**
     * Resolve an allocatable {@link GPUDeviceTO} for the VM. XenServer
     * resolves the GPU group from the vGPU profile's card and falls back
     * to the legacy lookup; other hypervisors delegate to
     * {@link org.apache.cloudstack.gpu.GpuService}.
     */
    GPUDeviceTO getGPUDevice(VirtualMachine vm, long hostId, VgpuProfileVO vgpuProfile, int gpuCount);

    /**
     * Resolve a {@link GPUDeviceTO} using the legacy group-name / vGPU-type
     * path. Throws {@link com.cloud.utils.exception.CloudRuntimeException}
     * when the host has no matching capacity.
     */
    GPUDeviceTO getGPUDevice(long hostId, String groupName, String vgpuType);

    /**
     * Persist updated GPU group capacity for a host within a single
     * transaction.
     */
    void updateGPUDetails(long hostId, HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails);

    /**
     * Refresh GPU group capacity on the host after a VM stop, also
     * deallocating per-VM GPU devices when the auto-detach setting is on.
     */
    void updateGPUDetailsForVmStop(VirtualMachine vm, GPUDeviceTO gpuDevice);

    /**
     * Allocate GPU devices to the VM (when the device TO carries explicit
     * device IDs) and refresh group capacity on the host.
     */
    void updateGPUDetailsForVmStart(long hostId, long vmId, GPUDeviceTO gpuDevice);

    /**
     * Ask the host agent for current GPU usage stats and merge any reported
     * devices into the persisted host-GPU view. Returns {@code null} when
     * the agent does not support the command or returns an error.
     */
    HashMap<String, HashMap<String, VgpuTypesInfo>> getGPUStatistics(HostVO host);

    /**
     * Merge agent-reported GPU devices into the host's GPU view. When the
     * host is persisted ({@code id > 0}) the devices are added through
     * {@link org.apache.cloudstack.gpu.GpuService}; when GPU devices are
     * present the returned details are sourced from the central service,
     * otherwise the supplied {@code groupDetails} are returned as-is.
     */
    HashMap<String, HashMap<String, VgpuTypesInfo>> getGroupDetails(HostVO host,
            List<VgpuTypesInfo> gpuDevices,
            HashMap<String, HashMap<String, VgpuTypesInfo>> groupDetails);
}
