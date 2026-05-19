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

import java.util.List;
import java.util.Map;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.host.Host;
import com.cloud.storage.VolumeVO;

public interface VmLiveMigrationOrchestrationService {

    boolean isVMUsingLocalStorage(VMInstanceVO vm);

    VirtualMachine migrateVirtualMachine(Long vmId, Host destinationHost) throws ResourceUnavailableException, ConcurrentOperationException,
            ManagementServerException, VirtualMachineMigrationException;

    boolean isAnyVmVolumeUsingLocalStorage(List<VolumeVO> volumes);

    boolean isAllVmVolumesOnZoneWideStore(List<VolumeVO> volumes);

    boolean isVmCanBeMigratedWithoutStorage(Host srcHost, Host destinationHost, List<VolumeVO> volumes, Map<String, String> volumeToPool);

    Host chooseVmMigrationDestinationUsingVolumePoolMap(VMInstanceVO vm, Host srcHost, Map<Long, Long> volToPoolObjectMap);

    VirtualMachine migrateVirtualMachineWithVolume(Long vmId, Host destinationHost, Map<String, String> volumeToPool) throws ResourceUnavailableException,
            ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException;
}
