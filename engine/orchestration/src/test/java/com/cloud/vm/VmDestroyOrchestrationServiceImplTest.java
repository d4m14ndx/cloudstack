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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.gpu.GpuService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.RestoreVMSnapshotAnswer;
import com.cloud.agent.api.RestoreVMSnapshotCommand;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.class)
public class VmDestroyOrchestrationServiceImplTest {

    private static final String VM_UUID = "vm-uuid";
    private static final long VM_ID = 7L;
    private static final long HOST_ID = 42L;
    private static final String INSTANCE_NAME = "i-2-7-VM";

    @InjectMocks
    private VmDestroyOrchestrationServiceImpl service;

    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VMSnapshotDao vmSnapshotDao;
    @Mock
    private VMSnapshotManager vmSnapshotMgr;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private GpuService gpuService;
    @Mock
    private BackupManager backupManager;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private VMInstanceVO vm;
    @Mock
    private UserVmVO userVm;

    @Test
    public void destroy_missingVm_doesNothing() throws Exception {
        service.destroy(VM_UUID, true);

        verify(virtualMachineManager, never()).advanceStop(eq(VM_UUID), anyBoolean());
        verify(gpuService, never()).deallocateAllGpuDevicesForVm(VM_ID);
    }

    @Test
    public void destroy_expungeTrue_stopsDeletesSnapshotsDeallocatesGpuAndTransitions() throws Exception {
        prepareDestroyableVm(HypervisorType.KVM);
        when(vmSnapshotMgr.deleteAllVMSnapshots(VM_ID, null)).thenReturn(true);
        when(virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.DestroyRequested, HOST_ID)).thenReturn(true);
        when(virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, HOST_ID)).thenReturn(true);

        try (MockedStatic<Transaction> transaction = Mockito.mockStatic(Transaction.class)) {
            transaction.when(() -> Transaction.execute(Mockito.<TransactionCallbackWithException<Boolean, CloudRuntimeException>>any()))
                    .thenAnswer(invocation -> {
                        TransactionCallbackWithException<Boolean, CloudRuntimeException> callback = invocation.getArgument(0);
                        return callback.doInTransaction(null);
                    });

            service.destroy(VM_UUID, true);
        }

        verify(virtualMachineManager).advanceStop(VM_UUID, VirtualMachineManagerImpl.VmDestroyForcestop.value());
        verify(vmSnapshotMgr).deleteAllVMSnapshots(VM_ID, null);
        verify(gpuService).deallocateAllGpuDevicesForVm(VM_ID);
        verify(backupManager).checkAndRemoveBackupOfferingBeforeExpunge(vm);
        verify(virtualMachineManager).stateTransitTo(vm, VirtualMachine.Event.DestroyRequested, HOST_ID);
        verify(virtualMachineManager).stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, HOST_ID);
    }

    @Test
    public void destroy_unexpectedResourceUnavailableFromStop_wrapsAsCloudRuntimeException() throws Exception {
        prepareDestroyableVm(HypervisorType.KVM);
        ResourceUnavailableException unavailable = new ResourceUnavailableException("stop failed", VirtualMachine.class, VM_ID);
        Mockito.doThrow(unavailable).when(virtualMachineManager).advanceStop(VM_UUID, VirtualMachineManagerImpl.VmDestroyForcestop.value());

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.destroy(VM_UUID, false));

        assertTrue(exception.getMessage().contains("Unable to stop vm " + VM_UUID));
    }

    @Test
    public void deleteVMSnapshots_nonVmwareFailure_throwsExistingMessage() {
        prepareVm(HypervisorType.KVM);
        when(vm.toString()).thenReturn("vm-for-snapshot-delete");

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.deleteVMSnapshots(vm, false));

        assertTrue(exception.getMessage().contains("Unable to delete Instance Snapshots for vm-for-snapshot-delete"));
    }

    @Test
    public void deleteVMSnapshots_vmwareExpunge_deletesOnlyDatabaseRows() {
        prepareVm(HypervisorType.VMware);

        service.deleteVMSnapshots(vm, true);

        verify(vmSnapshotMgr, never()).deleteAllVMSnapshots(VM_ID, null);
        verify(vmSnapshotMgr).deleteVMSnapshotsFromDB(VM_ID, false);
    }

    @Test
    public void checkVmOnHost_powerOffAnswer_returnsFalse() throws Exception {
        prepareVm(HypervisorType.KVM);
        CheckVirtualMachineCommand command = new CheckVirtualMachineCommand(INSTANCE_NAME);
        when(agentMgr.send(eq(HOST_ID), any(CheckVirtualMachineCommand.class)))
                .thenReturn(new CheckVirtualMachineAnswer(command, PowerState.PowerOff, null));

        assertFalse(service.checkVmOnHost(vm, HOST_ID));

        verify(userVmDao, never()).findById(VM_ID);
    }

    @Test
    public void checkVmOnHost_restoreCommandFailureStillReturnsTrue() throws Exception {
        prepareVm(HypervisorType.KVM);
        CheckVirtualMachineCommand checkCommand = new CheckVirtualMachineCommand(INSTANCE_NAME);
        RestoreVMSnapshotCommand restoreCommand = Mockito.mock(RestoreVMSnapshotCommand.class);
        when(agentMgr.send(eq(HOST_ID), any(CheckVirtualMachineCommand.class)))
                .thenReturn(new CheckVirtualMachineAnswer(checkCommand, PowerState.PowerOn, null));
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(Collections.emptyList());
        when(vmSnapshotMgr.createRestoreCommand(userVm, Collections.emptyList())).thenReturn(restoreCommand);
        when(agentMgr.send(HOST_ID, restoreCommand)).thenReturn(new RestoreVMSnapshotAnswer(restoreCommand, false, "restore failed"));

        assertTrue(service.checkVmOnHost(vm, HOST_ID));
    }

    private void prepareDestroyableVm(HypervisorType hypervisorType) {
        prepareVm(hypervisorType);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
    }

    private void prepareVm(HypervisorType hypervisorType) {
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getInstanceName()).thenReturn(INSTANCE_NAME);
        when(vm.getHypervisorType()).thenReturn(hypervisorType);
    }
}
