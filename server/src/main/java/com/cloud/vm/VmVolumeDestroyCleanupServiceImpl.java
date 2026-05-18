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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.uservm.UserVm;

@Component
public class VmVolumeDestroyCleanupServiceImpl implements VmVolumeDestroyCleanupService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VolumeApiService volumeService;

    @Override
    public void detachVolumesFromVm(UserVm vm, List<VolumeVO> volumes) {
        for (VolumeVO volume : volumes) {
            // Create a volume context so VOLUME.DETACH events reference the volume rather than the VM.
            CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
            volumeContext.setEventDetails("Volume Type: " + volume.getVolumeType() + " Volume ID: " + volume.getUuid() + " Instance ID: " + vm.getUuid());
            volumeContext.setEventResourceType(ApiCommandResourceType.Volume);
            volumeContext.setEventResourceId(volume.getId());

            Volume detachResult = null;
            try {
                detachResult = volumeService.detachVolumeViaDestroyVM(volume.getInstanceId(), volume.getId());
            } finally {
                CallContext.unregister();
            }

            if (detachResult == null) {
                logger.error("DestroyVM remove volume - failed to detach and delete volume {} from instance {}", volume, vm);
            }
        }
    }

    @Override
    public void deleteVolumesFromVm(UserVmVO vm, List<VolumeVO> volumes, boolean expunge) {
        for (VolumeVO volume : volumes) {
            destroyVolumeInContext(vm, expunge, volume);
        }
    }

    @Override
    public void destroyVolumeInContext(UserVmVO vm, boolean expunge, VolumeVO volume) {
        // Create a volume context so VOLUME.DESTROY events reference the volume rather than the VM.
        CallContext volumeContext = CallContext.register(CallContext.current(), ApiCommandResourceType.Volume);
        volumeContext.setEventDetails("Volume Type: " + volume.getVolumeType() + " Volume ID: " + volume.getUuid() + " Instance ID: " + vm.getUuid());
        volumeContext.setEventResourceType(ApiCommandResourceType.Volume);
        volumeContext.setEventResourceId(volume.getId());
        try {
            Volume result = volumeService.destroyVolume(volume.getId(), CallContext.current().getCallingAccount(), expunge, false);

            if (result == null) {
                logger.error("DestroyVM remove volume - failed to delete volume {} from instance {}", volume, vm);
            }
        } finally {
            CallContext.unregister();
        }
    }
}
