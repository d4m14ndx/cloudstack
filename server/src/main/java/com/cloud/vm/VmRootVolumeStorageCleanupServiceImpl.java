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

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.manager.Commands;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Spring-component implementation of {@link VmRootVolumeStorageCleanupService}.
 * Extracted from {@link UserVmManagerImpl} as slice 18 of the Phase 4
 * decomposition.
 *
 * <p>The behaviour preserves the original three private helpers
 * verbatim: {@code handleManagedStorage}, {@code handleTargetsForVMware},
 * and {@code sendModifyTargetsCommand}. Method names changed to public
 * ones so the contract is part of the bean's API surface; the warn-log
 * format strings are unchanged for grep continuity.
 *
 * @see VmRootVolumeStorageCleanupService
 */
@Component
public class VmRootVolumeStorageCleanupServiceImpl implements VmRootVolumeStorageCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(VmRootVolumeStorageCleanupServiceImpl.class);

    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private VolumeOrchestrationService volumeMgr;

    @Override
    public void cleanupRootVolumeOnManagedStorage(UserVmVO vm, VolumeVO root) {
        if (Volume.State.Allocated.equals(root.getState())) {
            return;
        }

        StoragePoolVO storagePool = storagePoolDao.findById(root.getPoolId());

        if (storagePool != null && storagePool.isManaged()) {
            Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();

            if (hostId != null) {
                // default findById() won't search entries with removed field not null
                Host host = hostDao.findById(hostId);
                if (host == null) {
                    logger.warn("Host {} not found", hostId);
                    return;
                }

                VolumeInfo volumeInfo = volFactory.getVolume(root.getId());

                final Command cmd;

                if (host.getHypervisorType() == HypervisorType.XenServer) {
                    DiskTO disk = new DiskTO(volumeInfo.getTO(), root.getDeviceId(), root.getPath(), root.getVolumeType());

                    // it's OK in this case to send a detach command to the host for a root volume as this
                    // will simply lead to the SR that supports the root volume being removed
                    cmd = new DettachCommand(disk, vm.getInstanceName());

                    DettachCommand detachCommand = (DettachCommand)cmd;

                    detachCommand.setManaged(true);

                    detachCommand.setStorageHost(storagePool.getHostAddress());
                    detachCommand.setStoragePort(storagePool.getPort());

                    detachCommand.set_iScsiName(root.get_iScsiName());
                }
                else if (host.getHypervisorType() == HypervisorType.VMware) {
                    PrimaryDataStore primaryDataStore = (PrimaryDataStore)volumeInfo.getDataStore();
                    Map<String, String> details = primaryDataStore.getDetails();

                    if (details == null) {
                        details = new HashMap<>();

                        primaryDataStore.setDetails(details);
                    }

                    details.put(DiskTO.MANAGED, Boolean.TRUE.toString());

                    cmd = new DeleteCommand(volumeInfo.getTO());
                }
                else if (host.getHypervisorType() == HypervisorType.KVM) {
                    cmd = null;
                }
                else {
                    throw new CloudRuntimeException("This hypervisor type is not supported on managed storage for this command.");
                }

                if (cmd != null) {
                    Commands cmds = new Commands(Command.OnError.Stop);

                    cmds.addCommand(cmd);

                    try {
                        agentMgr.send(hostId, cmds);
                    } catch (Exception ex) {
                        throw new CloudRuntimeException(ex.getMessage());
                    }

                    if (!cmds.isSuccessful()) {
                        for (Answer answer : cmds.getAnswers()) {
                            if (!answer.getResult()) {
                                logger.warn("Failed to reset vm {} due to: {}", vm, answer.getDetails());

                                throw new CloudRuntimeException("Unable to reset " + vm + " due to " + answer.getDetails());
                            }
                        }
                    }
                }

                // root.getPoolId() should be null if the VM we are detaching the disk from has never been started before
                DataStore dataStore = root.getPoolId() != null ? dataStoreMgr.getDataStore(root.getPoolId(), DataStoreRole.Primary) : null;

                volumeMgr.revokeAccess(volFactory.getVolume(root.getId()), host, dataStore);

                if (dataStore != null) {
                    handleTargetsForVMware(host.getId(), storagePool.getHostAddress(), storagePool.getPort(), root.get_iScsiName());
                }
            }
        }
    }

    /**
     * VMware-only follow-up after the per-host command above: broadcast a
     * {@link ModifyTargetsCommand} that drops the dynamic iSCSI target
     * for {@code iScsiName} across every host in the host's cluster.
     * No-op on non-VMware hypervisors.
     */
    protected void handleTargetsForVMware(long hostId, String storageAddress, int storagePort, String iScsiName) {
        HostVO host = hostDao.findById(hostId);

        if (host.getHypervisorType() == HypervisorType.VMware) {
            ModifyTargetsCommand cmd = new ModifyTargetsCommand();

            List<Map<String, String>> targets = new ArrayList<>();

            Map<String, String> target = new HashMap<>();

            target.put(ModifyTargetsCommand.STORAGE_HOST, storageAddress);
            target.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePort));
            target.put(ModifyTargetsCommand.IQN, iScsiName);

            targets.add(target);

            cmd.setTargets(targets);
            cmd.setApplyToAllHostsInCluster(true);
            cmd.setAdd(false);
            cmd.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

            sendModifyTargetsCommand(cmd, host);
        }
    }

    /**
     * Best-effort {@code easySend} of the modify-targets command — both
     * a missing answer and a failed answer are logged but not raised, so
     * a temporary host hiccup does not abort the restore flow.
     */
    protected void sendModifyTargetsCommand(ModifyTargetsCommand cmd, HostVO host) {
        Answer answer = agentMgr.easySend(host.getId(), cmd);

        if (answer == null) {
            String msg = "Unable to get an answer to the modify targets command";

            logger.warn(msg);
        }
        else if (!answer.getResult()) {
            String msg = String.format("Unable to modify target on the following host: %s", host);

            logger.warn(msg);
        }
    }
}
