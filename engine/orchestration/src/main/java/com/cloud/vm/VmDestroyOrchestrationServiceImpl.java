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

import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.gpu.GpuService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.RestoreVMSnapshotAnswer;
import com.cloud.agent.api.RestoreVMSnapshotCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Component
public class VmDestroyOrchestrationServiceImpl implements VmDestroyOrchestrationService {

    private static final Logger logger = LogManager.getLogger(VmDestroyOrchestrationServiceImpl.class);

    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected VMSnapshotDao vmSnapshotDao;
    @Inject
    protected VMSnapshotManager vmSnapshotMgr;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected GpuService gpuService;
    @Inject
    protected BackupManager backupManager;
    @Inject
    @Lazy
    protected VirtualMachineManager virtualMachineManager;

    @Override
    public void destroy(final String vmUuid, final boolean expunge) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        if (vm == null || vm.getState() == State.Destroyed || vm.getState() == State.Expunging || vm.getRemoved() != null) {
            logger.debug("Unable to find vm or vm is destroyed: {}", vm);
            return;
        }

        logger.debug("Destroying vm {}, expunge flag {}", vm, (expunge ? "on" : "off"));

        advanceStop(vmUuid);

        deleteVMSnapshots(vm, expunge);

        gpuService.deallocateAllGpuDevicesForVm(vm.getId());

        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<CloudRuntimeException>() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) throws CloudRuntimeException {
                VMInstanceVO vm = vmDao.findByUuid(vmUuid);
                try {
                    if (!virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.DestroyRequested, vm.getHostId())) {
                        logger.debug("Unable to destroy the vm because it is not in the correct state: {}", vm);
                        throw new CloudRuntimeException("Unable to destroy " + vm);
                    } else {
                        if (expunge) {
                            backupManager.checkAndRemoveBackupOfferingBeforeExpunge(vm);
                            if (!virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, vm.getHostId())) {
                                logger.debug("Unable to expunge the vm because it is not in the correct state: {}", vm);
                                throw new CloudRuntimeException("Unable to expunge " + vm);
                            }
                        }
                    }
                } catch (final NoTransitionException e) {
                    String message = String.format("Unable to destroy %s due to [%s].", vm.toString(), e.getMessage());
                    logger.debug(message, e);
                    throw new CloudRuntimeException(message, e);
                }
            }
        });
    }

    private void advanceStop(final String vmUuid) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        try {
            virtualMachineManager.advanceStop(vmUuid, VirtualMachineManagerImpl.VmDestroyForcestop.value());
        } catch (ResourceUnavailableException e) {
            if (e instanceof AgentUnavailableException) {
                throw (AgentUnavailableException)e;
            }
            throw new CloudRuntimeException("Unable to stop vm " + vmUuid, e);
        }
    }

    /**
     * Delete vm snapshots depending on vm's hypervisor type. For Vmware, vm snapshots removal is delegated to vm cleanup thread
     * to reduce tasks sent to hypervisor (one tasks to delete vm snapshots and vm itself
     * instead of one task for each vm snapshot plus another for the vm)
     * @param vm vm
     * @param expunge indicates if vm should be expunged
     */
    @Override
    public void deleteVMSnapshots(VMInstanceVO vm, boolean expunge) {
        if (!vm.getHypervisorType().equals(HypervisorType.VMware)) {
            if (!vmSnapshotMgr.deleteAllVMSnapshots(vm.getId(), null)) {
                logger.debug("Unable to delete all Snapshots for {}", vm);
                throw new CloudRuntimeException("Unable to delete Instance Snapshots for " + vm);
            }
        } else {
            if (expunge) {
                vmSnapshotMgr.deleteVMSnapshotsFromDB(vm.getId(), false);
            }
        }
    }

    @Override
    public boolean checkVmOnHost(final VirtualMachine vm, final long hostId) throws AgentUnavailableException, OperationTimedoutException {
        final Answer answer = agentMgr.send(hostId, new CheckVirtualMachineCommand(vm.getInstanceName()));
        if (answer == null || !answer.getResult()) {
            return false;
        }
        if (answer instanceof CheckVirtualMachineAnswer) {
            final CheckVirtualMachineAnswer vmAnswer = (CheckVirtualMachineAnswer)answer;
            if (vmAnswer.getState() == PowerState.PowerOff) {
                return false;
            }
        }

        UserVmVO userVm = userVmDao.findById(vm.getId());
        if (userVm != null) {
            List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
            RestoreVMSnapshotCommand command = vmSnapshotMgr.createRestoreCommand(userVm, vmSnapshots);
            if (command != null) {
                RestoreVMSnapshotAnswer restoreVMSnapshotAnswer = (RestoreVMSnapshotAnswer) agentMgr.send(hostId, command);
                if (restoreVMSnapshotAnswer == null || !restoreVMSnapshotAnswer.getResult()) {
                    logger.warn("Unable to restore the Instance Snapshot from image file after live migration of Instance with vmsnapshots: {}", restoreVMSnapshotAnswer == null ? "null answer" : restoreVMSnapshotAnswer.getDetails());
                }
            }
        }

        return true;
    }
}
