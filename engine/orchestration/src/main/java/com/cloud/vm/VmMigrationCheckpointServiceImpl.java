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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RecreateCheckpointsCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class VmMigrationCheckpointServiceImpl implements VmMigrationCheckpointService {

    private static final Logger logger = LogManager.getLogger(VmMigrationCheckpointServiceImpl.class);

    @Inject
    private AgentManager agentManager;
    @Inject
    private VolumeOrchestrationService volumeOrchestrationService;
    @Inject
    private SnapshotManager snapshotManager;
    @Inject
    private VolumeDao volumeDao;

    @Override
    public void endSnapshotChainForVolumes(Map<Volume, StoragePool> volumeToPoolMap, HypervisorType hypervisorType) {
        Set<Volume> volumes = volumeToPoolMap.keySet();
        volumes.forEach(volume -> {
            Volume volumeOnDestination = volumeDao.findByPoolIdName(volumeToPoolMap.get(volume).getId(), volume.getName());
            snapshotManager.endSnapshotChainForVolume(volumeOnDestination.getId(), hypervisorType);
        });
    }

    @Override
    public void recreateCheckpointsKvmOnVmAfterMigration(VMInstanceVO vm, long hostId) {
        if (!HypervisorType.KVM.equals(vm.getHypervisorType())) {
            logger.debug("Will not recreate checkpoint on VM as it is not running on KVM, thus it is not needed.");
            return;
        }

        List<VolumeObjectTO> volumes = getVmVolumesWithCheckpointsToRecreate(vm);

        if (volumes.isEmpty()) {
            logger.debug("Will not recreate checkpoints on VM as its volumes do not have any checkpoints associated with them.");
            return;
        }

        RecreateCheckpointsCommand recreateCheckpointsCommand = new RecreateCheckpointsCommand(volumes, vm.getInstanceName());
        Answer answer = null;
        try {
            logger.debug(String.format("Recreating the volume checkpoints with URLs [%s] of volumes [%s] on %s as part of the migration process.",
                    volumes.stream().map(VolumeObjectTO::getCheckpointPaths).collect(Collectors.toList()), volumes, vm));
            answer = agentManager.send(hostId, recreateCheckpointsCommand);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.error(String.format("Exception while sending command to host [%s] to recreate checkpoints with URLs [%s] of volumes [%s] on %s due to: [%s].",
                    hostId, volumes.stream().map(VolumeObjectTO::getCheckpointPaths).collect(Collectors.toList()), volumes, vm, e.getMessage()), e);
            throw new CloudRuntimeException(e);
        } finally {
            if (answer != null && answer.getResult()) {
                logger.debug(String.format("Successfully recreated checkpoints on VM [%s].", vm));
                return;
            }

            logger.debug(String.format("Migration on VM [%s] was successful; however, we weren't able to recreate the checkpoints on it. Marking the snapshot chain as ended." +
                    " Next snapshot will create a new snapshot chain.", vm));

            volumes.forEach(volumeObjectTO -> snapshotManager.endSnapshotChainForVolume(volumeObjectTO.getId(), HypervisorType.KVM));
        }
    }

    @Override
    public List<VolumeObjectTO> getVmVolumesWithCheckpointsToRecreate(VMInstanceVO vm) {
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        List<VolumeObjectTO> volumes = new ArrayList<>();

        for (VolumeVO volume : vmVolumes) {
            Pair<List<String>, Set<String>> volumeCheckpointPathsAndImageStoreUrls =
                    volumeOrchestrationService.getVolumeCheckpointPathsAndImageStoreUrls(volume.getId(), HypervisorType.KVM);
            if (volumeCheckpointPathsAndImageStoreUrls.first().isEmpty()) {
                continue;
            }
            VolumeObjectTO volumeTo = new VolumeObjectTO();
            volumeTo.setCheckpointPaths(volumeCheckpointPathsAndImageStoreUrls.first());
            volumeTo.setCheckpointImageStoreUrls(volumeCheckpointPathsAndImageStoreUrls.second());
            volumeTo.setPath(volume.getPath());
            volumes.add(volumeTo);
        }
        return volumes;
    }
}
