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

import org.apache.cloudstack.framework.jobs.Outcome;

import org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd;

/**
 * Handles the volume-detach lifecycle extracted from
 * {@link VolumeApiServiceImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 9).
 *
 * <p>The god class retains a {@code @ReflectionUse} shim
 * {@code orchestrateDetachVolumeFromVM(VmWorkDetachVolume)} so that the
 * {@link com.cloud.vm.VmWorkJobHandlerProxy} reflection dispatch continues
 * to resolve the method on {@code VolumeApiServiceImpl}, while the actual
 * orchestration logic lives in {@link VolumeDetachServiceImpl}.</p>
 */
public interface VolumeDetachService {

    /**
     * Primary API entry point for detaching a volume from a VM.
     * Validates caller permissions, VM/volume state, and snapshot
     * constraints before routing either through the job queue (running VM)
     * or directly to {@link #orchestrateDetachVolumeFromVM(long, long)}.
     *
     * @param cmd the detach command carrying volume id / (deviceId, vmId) tuple
     * @return the detached {@link Volume}
     */
    Volume detachVolumeFromVM(DetachVolumeCmd cmd);

    /**
     * Destroy-path entry: detaches a volume as part of VM destruction.
     * Skips the async job queue and delegates directly to
     * {@link #orchestrateDetachVolumeFromVM(long, long)}.
     *
     * @param vmId     id of the VM being destroyed
     * @param volumeId id of the volume to detach
     * @return the detached {@link Volume}
     */
    Volume detachVolumeViaDestroyVM(long vmId, long volumeId);

    /**
     * Core orchestration: sends a {@link com.cloud.agent.api.to.DiskTO}
     * DettachCommand to the host (when the VM is running or the pool
     * requires it), marks the volume row as detached, revokes primary-store
     * access, handles VMware iSCSI target removal, and publishes the detach
     * usage event.
     *
     * @param vmId     id of the VM from which the volume is detached
     * @param volumeId id of the volume to detach
     * @return the freshly reloaded {@link Volume} after detach
     */
    Volume orchestrateDetachVolumeFromVM(long vmId, long volumeId);

    /**
     * Submits a {@link com.cloud.vm.VmWorkDetachVolume} job to the VM
     * work queue so that detach is serialised against other operations on
     * the same VM.
     *
     * @param vmId     id of the VM (may be null only when volume carries instance id)
     * @param volumeId id of the volume to detach
     * @return an {@link Outcome} that resolves to the detached {@link Volume}
     */
    Outcome<Volume> detachVolumeFromVmThroughJobQueue(Long vmId, Long volumeId);
}
