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

import org.apache.cloudstack.api.command.user.volume.CheckAndRepairVolumeCmd;
import org.apache.cloudstack.framework.jobs.Outcome;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.utils.Pair;

/**
 * Helpers backing {@link VolumeApiServiceImpl#checkAndRepairVolume} as part of
 * the Phase 4 Spring-component decomposition.
 *
 * <p>This extracted slice owns three responsibilities that used to live in
 * {@link VolumeApiServiceImpl}: validation for check/repair eligibility,
 * direct repair orchestration against {@link org.apache.cloudstack.engine.subsystem.api.storage.VolumeService},
 * and the VM-work queue path used when the target volume is attached to a VM.
 * The {@code @ReflectionUse} shim for
 * {@code orchestrateCheckAndRepairVolume(VmWorkCheckAndRepairVolume)} remains
 * on the god class so {@link com.cloud.vm.VmWorkJobHandlerProxy} reflection
 * dispatch continues to resolve on {@code VolumeApiServiceImpl}.</p>
 */
public interface VolumeCheckAndRepairService {

    Pair<String, String> checkAndRepairVolume(CheckAndRepairVolumeCmd cmd) throws ResourceAllocationException;

    Pair<String, String> handleCheckAndRepairVolume(Long volumeId, String repair);

    Pair<String, String> handleCheckAndRepairVolumeJob(Long vmId, Long volumeId, String repair) throws ResourceAllocationException;

    void validationsForCheckVolumeOperation(VolumeVO volume);

    void validateVMforCheckVolumeOperation(Long vmId, String volumeName);

    Pair<String, String> orchestrateCheckAndRepairVolume(Long volumeId, String repair);

    Outcome<Pair> checkAndRepairVolumeThroughJobQueue(Long vmId, Long volumeId, String repair);
}
