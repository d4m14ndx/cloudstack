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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

/**
 * VMware managed-iSCSI dynamic-target cleanup — extracted from
 * {@link VirtualMachineManagerImpl}.
 *
 * @see VmIscsiTargetManager
 */
@Component
public class VmIscsiTargetManagerImpl implements VmIscsiTargetManager {

    private static final Logger logger = LogManager.getLogger(VmIscsiTargetManagerImpl.class);

    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private AgentManager agentMgr;

    @Override
    public List<Map<String, String>> getTargets(Long hostId, long vmId) {
        List<Map<String, String>> targets = new ArrayList<>();

        HostVO hostVO = hostDao.findById(hostId);

        if (hostVO == null || hostVO.getHypervisorType() != HypervisorType.VMware) {
            return targets;
        }

        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);

        if (CollectionUtils.isEmpty(volumes)) {
            return targets;
        }

        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePoolVO = storagePoolDao.findById(volume.getPoolId());

            if (storagePoolVO != null && storagePoolVO.isManaged()) {
                Map<String, String> target = new HashMap<>();

                target.put(ModifyTargetsCommand.STORAGE_HOST, storagePoolVO.getHostAddress());
                target.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));
                target.put(ModifyTargetsCommand.IQN, volume.get_iScsiName());

                targets.add(target);
            }
        }

        return targets;
    }

    @Override
    public void removeDynamicTargets(long hostId, List<Map<String, String>> targets) {
        ModifyTargetsCommand cmd = new ModifyTargetsCommand();

        cmd.setTargets(targets);
        cmd.setApplyToAllHostsInCluster(true);
        cmd.setAdd(false);
        cmd.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

        sendModifyTargetsCommand(cmd, hostId);
    }

    protected void sendModifyTargetsCommand(ModifyTargetsCommand cmd, long hostId) {
        Answer answer = agentMgr.easySend(hostId, cmd);

        if (answer == null) {
            logger.warn("Unable to get an answer to the modify targets command. Targets [{}].",
                    () -> cmd.getTargets().stream().map(target -> target.toString()).collect(Collectors.joining(", ")));
            return;
        }

        if (!answer.getResult()) {
            logger.warn("Unable to modify targets [{}] on the host [{}].",
                    () -> cmd.getTargets().stream().map(target -> target.toString()).collect(Collectors.joining(", ")), () -> hostId);
        }
    }
}
