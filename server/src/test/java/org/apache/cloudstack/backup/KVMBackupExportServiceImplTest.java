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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.backup.CreateImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.DeleteVmCheckpointCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeBackupCmd;
import org.apache.cloudstack.api.command.admin.backup.ListImageTransfersCmd;
import org.apache.cloudstack.api.command.admin.backup.ListVmCheckpointsCmd;
import org.apache.cloudstack.api.command.admin.backup.StartBackupCmd;
import org.apache.cloudstack.api.response.CheckpointResponse;
import org.apache.cloudstack.api.response.ImageTransferResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.ImageTransferDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class KVMBackupExportServiceImplTest {

    private static final long VM_ID = 11L;
    private static final long HOST_ID = 22L;
    private static final long VOLUME_ID = 33L;
    private static final long POOL_ID = 44L;

    @InjectMocks
    private KVMBackupExportServiceImpl service;

    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private BackupDao backupDao;
    @Mock
    private ImageTransferDao imageTransferDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VolumeDataFactory volumeDataFactory;
    @Mock
    private AgentManager agentManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    private StoragePoolHostDao storagePoolHostDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private AccountService accountService;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private CallContext callContext;
    @Mock
    private User user;

    private VMInstanceVO vm;
    private VolumeVO volume;

    @Before
    public void setUp() {
        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getInstanceName()).thenReturn("i-2-11-VM");
        when(vm.getDataCenterId()).thenReturn(101L);
        when(vm.getAccountId()).thenReturn(201L);
        when(vm.getDomainId()).thenReturn(301L);

        volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(VOLUME_ID);
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.getPoolId()).thenReturn(POOL_ID);
        when(volume.getPath()).thenReturn("volume-path");
        when(volume.getDataCenterId()).thenReturn(101L);
        when(volume.getAccountId()).thenReturn(201L);
        when(volume.getDomainId()).thenReturn(301L);
    }

    @Test
    public void createBackupPersistsQueuedBackupWithHostAndCheckpointIds() {
        stubVmForCreateBackup(VirtualMachine.State.Running, HOST_ID, Map.of(VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-active"));
        when(backupDao.persist(any(BackupVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Backup backup = service.createBackup(startBackupCmd(VM_ID, "backup-name", "description"));

        assertNotNull(backup);
        ArgumentCaptor<BackupVO> captor = ArgumentCaptor.forClass(BackupVO.class);
        verify(backupDao).persist(captor.capture());
        BackupVO persisted = captor.getValue();
        assertEquals(Backup.Status.Queued, persisted.getStatus());
        assertEquals(Long.valueOf(VM_ID), persisted.getVmId());
        assertEquals("backup-name", persisted.getName());
        assertEquals("description", persisted.getDescription());
        assertEquals(Long.valueOf(HOST_ID), persisted.getHostId());
        assertEquals("ckp-active", persisted.getFromCheckpointId());
        assertNotNull(persisted.getToCheckpointId());
        assertTrue(persisted.getToCheckpointId().startsWith("ckp-"));
    }

    @Test
    public void createBackupRejectsMissingVm() {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(null);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.createBackup(startBackupCmd(VM_ID, "backup", null)));

        assertTrue(exception.getMessage().contains("Instance not found"));
    }

    @Test
    public void createBackupRejectsInvalidVmState() {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Migrating);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.createBackup(startBackupCmd(VM_ID, "backup", null)));

        assertTrue(exception.getMessage().contains("running or stopped"));
    }

    @Test
    public void createBackupRejectsNonReadyVolumes() {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume));
        when(volume.getState()).thenReturn(Volume.State.Allocated);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.createBackup(startBackupCmd(VM_ID, "backup", null)));

        assertTrue(exception.getMessage().contains("volume-uuid"));
    }

    @Test
    public void createBackupRejectsMissingHost() {
        stubVmForCreateBackup(VirtualMachine.State.Stopped, null, Collections.emptyMap());

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.createBackup(startBackupCmd(VM_ID, "backup", null)));

        assertTrue(exception.getMessage().contains("Host cannot be determined"));
    }

    @Test
    public void startBackupSendsStartCommandAndUpdatesReadyForImageTransfer() throws Exception {
        BackupVO backup = backup(501L, VM_ID, HOST_ID);
        backup.setFromCheckpointId("ckp-from");
        backup.setToCheckpointId("ckp-to");
        when(backupDao.findById(501L)).thenReturn(backup);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume));
        stubStoragePool();
        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        byte[] passphrase = "secret".getBytes();
        when(volumeInfo.getPassphrase()).thenReturn(passphrase);
        when(volumeDataFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(Map.of(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, "123"));
        when(agentManager.send(eq(HOST_ID), any(StartBackupCommand.class))).thenReturn(new StartBackupAnswer(null, true, null, 456L));

        Backup result = service.startBackup(startBackupCmd(VM_ID, 501L));

        assertEquals(backup, result);
        ArgumentCaptor<StartBackupCommand> command = ArgumentCaptor.forClass(StartBackupCommand.class);
        verify(agentManager).send(eq(HOST_ID), command.capture());
        assertEquals("i-2-11-VM", command.getValue().getVmName());
        assertEquals("ckp-to", command.getValue().getToCheckpointId());
        assertEquals("ckp-from", command.getValue().getFromCheckpointId());
        assertEquals(Long.valueOf(123L), command.getValue().getFromCheckpointCreateTime());
        assertEquals("volume-uuid", command.getValue().getDiskPathUuidMap().get("/mnt/pool-uuid/volume-path"));
        assertEquals(passphrase, command.getValue().getDiskPathPassphraseMap().get("/mnt/pool-uuid/volume-path"));
        assertTrue(command.getValue().isStoppedVM());
        assertEquals(Long.valueOf(456L), backup.getCheckpointCreateTime());
        assertEquals(Backup.Status.ReadyForImageTransfer, backup.getStatus());
        verify(backupDao).update(501L, backup);
    }

    @Test
    public void createImageTransferSendsCreateCommandAndPersistsTicketAndUrl() throws Exception {
        try (MockedStatic<CallContext> callContextMock = mockStatic(CallContext.class)) {
            stubCallContext(callContextMock);
            stubUploadVolume();
            when(agentManager.send(eq(HOST_ID), any(CreateImageTransferCommand.class)))
                    .thenReturn(new CreateImageTransferAnswer(null, true, null, "ticket-1", "https://transfer.example/image"));
            when(imageTransferDao.persist(any(ImageTransferVO.class))).thenAnswer(invocation -> {
                ImageTransferVO transfer = invocation.getArgument(0);
                ReflectionTestUtils.setField(transfer, "id", 601L);
                return transfer;
            });

            ImageTransfer transfer = service.createImageTransfer(VOLUME_ID, null, ImageTransfer.Direction.upload, ImageTransfer.Format.cow);

            assertNotNull(transfer);
            ArgumentCaptor<CreateImageTransferCommand> command = ArgumentCaptor.forClass(CreateImageTransferCommand.class);
            verify(agentManager).send(eq(HOST_ID), command.capture());
            assertEquals(CreateImageTransferCommand.Backend.file, command.getValue().getBackend());
            assertEquals("upload", command.getValue().getDirection());
            assertEquals("/mnt/pool-uuid/volume-path", command.getValue().getFile());
            assertNotNull(command.getValue().getToken());
            assertNotEquals(command.getValue().getTransferId(), command.getValue().getToken());
            ArgumentCaptor<ImageTransferVO> transferCaptor = ArgumentCaptor.forClass(ImageTransferVO.class);
            verify(imageTransferDao).persist(transferCaptor.capture());
            assertEquals("https://transfer.example/image", transferCaptor.getValue().getTransferUrl());
            assertEquals(command.getValue().getToken(), transferCaptor.getValue().getSignedTicketId());
            assertEquals(ImageTransfer.Phase.transferring, transferCaptor.getValue().getPhase());
            verify(imageTransferDao).findUnfinishedByVolume(VOLUME_ID);
            verify(accountService).checkAccess(user, volume);
        }
    }

    @Test
    public void createImageTransferResponseIncludesPersistedSignedTicketId() throws Exception {
        try (MockedStatic<CallContext> callContextMock = mockStatic(CallContext.class)) {
            stubCallContext(callContextMock);
            stubUploadVolume();
            when(agentManager.send(eq(HOST_ID), any(CreateImageTransferCommand.class)))
                    .thenReturn(new CreateImageTransferAnswer(null, true, null, "ticket-1", "https://transfer.example/image"));
            when(imageTransferDao.persist(any(ImageTransferVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

            ImageTransferResponse response = service.createImageTransfer(createImageTransferCmd(VOLUME_ID, null,
                    ImageTransfer.Direction.upload, ImageTransfer.Format.cow));

            ArgumentCaptor<CreateImageTransferCommand> command = ArgumentCaptor.forClass(CreateImageTransferCommand.class);
            verify(agentManager).send(eq(HOST_ID), command.capture());
            assertEquals(command.getValue().getToken(), ReflectionTestUtils.getField(response, "signedTicketId"));
        }
    }

    @Test
    public void listImageTransfersResponseIncludesPersistedSignedTicketId() {
        ImageTransferVO transfer = nbdUploadTransfer();
        transfer.setSignedTicketId("ticket-1");
        when(imageTransferDao.listAll()).thenReturn(List.of(transfer));
        when(volumeDao.findByIdIncludingRemoved(VOLUME_ID)).thenReturn(volume);

        List<ImageTransferResponse> responses = service.listImageTransfers(listImageTransfersCmd());

        assertEquals(1, responses.size());
        assertEquals("ticket-1", ReflectionTestUtils.getField(responses.get(0), "signedTicketId"));
    }

    @Test
    public void createImageTransferValidatesVolumeBeforeAccess() {
        try (MockedStatic<CallContext> callContextMock = mockStatic(CallContext.class)) {
            stubCallContext(callContextMock);
            when(volumeDao.findById(VOLUME_ID)).thenReturn(null);

            assertThrows(CloudRuntimeException.class,
                    () -> service.createImageTransfer(VOLUME_ID, null, ImageTransfer.Direction.upload, ImageTransfer.Format.raw));

            verify(accountService, never()).checkAccess(any(User.class), any(VolumeVO.class));
        }
    }

    @Test
    public void createImageTransferRejectsOnlyUnfinishedExistingTransfer() {
        try (MockedStatic<CallContext> callContextMock = mockStatic(CallContext.class)) {
            stubCallContext(callContextMock);
            when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
            ImageTransferVO existing = new ImageTransferVO("existing", VOLUME_ID, HOST_ID, "/tmp/file",
                    ImageTransfer.Phase.transferring, ImageTransfer.Direction.upload, 201L, 301L, 101L);
            when(imageTransferDao.findUnfinishedByVolume(VOLUME_ID)).thenReturn(existing);

            assertThrows(CloudRuntimeException.class,
                    () -> service.createImageTransfer(VOLUME_ID, null, ImageTransfer.Direction.upload, ImageTransfer.Format.cow));

            verify(imageTransferDao, never()).findByVolume(anyLong());
        }
    }

    @Test
    public void createImageTransferStopsNbdServerWhenCreateCommandFailsAfterNbdStart() throws Exception {
        try (MockedStatic<CallContext> callContextMock = mockStatic(CallContext.class)) {
            stubCallContext(callContextMock);
            stubUploadVolume();
            when(agentManager.send(eq(HOST_ID), any(Command.class))).thenReturn(
                    new StartNBDServerAnswer(null, true, null),
                    new CreateImageTransferAnswer(null, false, "create failed"),
                    new Answer(null, true, null)
            );

            assertThrows(CloudRuntimeException.class,
                    () -> service.createImageTransfer(VOLUME_ID, null, ImageTransfer.Direction.upload, ImageTransfer.Format.raw));

            InOrder inOrder = inOrder(agentManager);
            inOrder.verify(agentManager).send(eq(HOST_ID), any(StartNBDServerCommand.class));
            inOrder.verify(agentManager).send(eq(HOST_ID), any(CreateImageTransferCommand.class));
            inOrder.verify(agentManager).send(eq(HOST_ID), any(StopNBDServerCommand.class));
            verify(imageTransferDao, never()).persist(any(ImageTransferVO.class));
        }
    }

    @Test
    public void finalizeTransferDoesNotUpdateOrRemoveWhenAgentFinalizeFails() throws Exception {
        ImageTransferVO transfer = nbdUploadTransfer();
        when(imageTransferDao.findById(701L)).thenReturn(transfer);
        when(agentManager.send(eq(HOST_ID), any(FinalizeImageTransferCommand.class)))
                .thenReturn(new Answer(null, false, "finalize failed"));

        assertThrows(CloudRuntimeException.class, () -> service.finalizeImageTransfer(701L));

        verify(imageTransferDao, never()).update(eq(701L), any(ImageTransferVO.class));
        verify(imageTransferDao, never()).remove(701L);
    }

    @Test
    public void finalizeTransferDoesNotUpdateOrRemoveWhenNbdStopFails() throws Exception {
        ImageTransferVO transfer = nbdUploadTransfer();
        when(imageTransferDao.findById(701L)).thenReturn(transfer);
        when(agentManager.send(eq(HOST_ID), any(Command.class))).thenReturn(
                new Answer(null, true, null),
                new Answer(null, false, "stop failed")
        );

        assertThrows(CloudRuntimeException.class, () -> service.finalizeImageTransfer(701L));

        verify(imageTransferDao, never()).update(eq(701L), any(ImageTransferVO.class));
        verify(imageTransferDao, never()).remove(701L);
    }

    @Test
    public void finalizeBackupFinalizesOpenTransfersStopsRunningVmRotatesCheckpointsAndKeepsBackupHistory() throws Exception {
        BackupVO backup = backup(801L, VM_ID, HOST_ID);
        backup.setToCheckpointId("ckp-new");
        backup.setCheckpointCreateTime(987L);
        ImageTransferVO transfer = nbdUploadTransfer();
        ReflectionTestUtils.setField(transfer, "id", 802L);
        when(backupDao.findById(801L)).thenReturn(backup);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(imageTransferDao.listByBackupId(801L)).thenReturn(List.of(transfer));
        when(imageTransferDao.findById(802L)).thenReturn(transfer);
        when(agentManager.send(eq(HOST_ID), any(Command.class))).thenReturn(
                new Answer(null, true, null),
                new Answer(null, true, null),
                new StopBackupAnswer(null, true, null)
        );
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(Map.of(
                VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-old",
                VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, "123"));

        Backup result = service.finalizeBackup(finalizeBackupCmd(VM_ID, 801L));

        assertEquals(backup, result);
        assertEquals(Backup.Status.BackedUp, backup.getStatus());
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.LAST_CHECKPOINT_ID, "ckp-old", false);
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME, "123", false);
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-new", false);
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, "987", false);
        verify(backupDao, never()).remove(801L);
    }

    @Test
    public void listVmCheckpointsReturnsActiveAndLastDetails() {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(Map.of(
                VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-active",
                VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, "100",
                VmDetailConstants.LAST_CHECKPOINT_ID, "ckp-last",
                VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME, "50"));

        List<CheckpointResponse> responses = service.listVmCheckpoints(listVmCheckpointsCmd(VM_ID));

        assertEquals(2, responses.size());
        assertEquals("ckp-active", ReflectionTestUtils.getField(responses.get(0), "id"));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(responses.get(0), "isActive"));
        assertEquals(new Date(100_000L), ReflectionTestUtils.getField(responses.get(0), "created"));
        assertEquals("ckp-last", ReflectionTestUtils.getField(responses.get(1), "id"));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(responses.get(1), "isActive"));
        assertEquals(new Date(50_000L), ReflectionTestUtils.getField(responses.get(1), "created"));
    }

    @Test
    public void deleteVmCheckpointDeletesOnlyActiveCheckpointAndPromotesLast() throws Exception {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(Map.of(
                VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-active",
                VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, "100",
                VmDetailConstants.LAST_CHECKPOINT_ID, "ckp-last",
                VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME, "50"));
        when(agentManager.send(eq(HOST_ID), any(DeleteVmCheckpointCommand.class))).thenReturn(new Answer(null, true, null));

        assertTrue(service.deleteVmCheckpoint(deleteVmCheckpointCmd(VM_ID, "ckp-active")));

        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-last", false);
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, "50", false);
        verify(vmInstanceDetailsDao).removeDetail(VM_ID, VmDetailConstants.LAST_CHECKPOINT_ID);
        verify(vmInstanceDetailsDao).removeDetail(VM_ID, VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME);
    }

    @Test
    public void deleteVmCheckpointIgnoresNonActiveCheckpoint() throws Exception {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(Map.of(VmDetailConstants.ACTIVE_CHECKPOINT_ID, "ckp-active"));

        assertTrue(service.deleteVmCheckpoint(deleteVmCheckpointCmd(VM_ID, "ckp-last")));

        verify(agentManager, never()).send(anyLong(), any(DeleteVmCheckpointCommand.class));
        verify(vmInstanceDetailsDao, never()).removeDetail(eq(VM_ID), any());
        verify(vmInstanceDetailsDao, never()).addDetail(eq(VM_ID), any(), any(), anyBoolean());
    }

    private void stubVmForCreateBackup(VirtualMachine.State state, Long hostId, Map<String, String> vmDetails) {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(state);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.emptyList());
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(3L, hostId));
        when(vmInstanceDetailsDao.listDetailsKeyPairs(VM_ID)).thenReturn(vmDetails);
    }

    private void stubCallContext(MockedStatic<CallContext> callContextMock) {
        callContextMock.when(CallContext::current).thenReturn(callContext);
        when(callContext.getCallingUser()).thenReturn(user);
    }

    private void stubUploadVolume() {
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        when(imageTransferDao.findUnfinishedByVolume(VOLUME_ID)).thenReturn(null);
        stubStoragePool();
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getDataCenterId()).thenReturn(101L);
        when(hostDao.findByDataCenterId(101L)).thenReturn(List.of(host));
    }

    private void stubStoragePool() {
        StoragePoolVO pool = new StoragePoolVO();
        pool.setId(POOL_ID);
        pool.setUuid("pool-uuid");
        pool.setDataCenterId(101L);
        pool.setScope(ScopeType.ZONE);
        pool.setPoolType(Storage.StoragePoolType.NetworkFilesystem);
        pool.setPath("/primary");
        when(primaryDataStoreDao.findById(POOL_ID)).thenReturn(pool);
    }

    private StartBackupCmd startBackupCmd(long vmId, String name, String description) {
        StartBackupCmd cmd = mock(StartBackupCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getName()).thenReturn(name);
        when(cmd.getDescription()).thenReturn(description);
        return cmd;
    }

    private StartBackupCmd startBackupCmd(long vmId, long entityId) {
        StartBackupCmd cmd = mock(StartBackupCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getEntityId()).thenReturn(entityId);
        return cmd;
    }

    private FinalizeBackupCmd finalizeBackupCmd(long vmId, long backupId) {
        FinalizeBackupCmd cmd = mock(FinalizeBackupCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        when(cmd.getBackupId()).thenReturn(backupId);
        return cmd;
    }

    private ListVmCheckpointsCmd listVmCheckpointsCmd(long vmId) {
        ListVmCheckpointsCmd cmd = mock(ListVmCheckpointsCmd.class);
        when(cmd.getVmId()).thenReturn(vmId);
        return cmd;
    }

    private DeleteVmCheckpointCmd deleteVmCheckpointCmd(long vmId, String checkpointId) {
        DeleteVmCheckpointCmd cmd = new DeleteVmCheckpointCmd();
        cmd.setVmId(vmId);
        cmd.setCheckpointId(checkpointId);
        return cmd;
    }

    private CreateImageTransferCmd createImageTransferCmd(long volumeId, Long backupId, ImageTransfer.Direction direction, ImageTransfer.Format format) {
        CreateImageTransferCmd cmd = mock(CreateImageTransferCmd.class);
        when(cmd.getVolumeId()).thenReturn(volumeId);
        when(cmd.getBackupId()).thenReturn(backupId);
        when(cmd.getDirection()).thenReturn(direction);
        when(cmd.getFormat()).thenReturn(format);
        return cmd;
    }

    private ListImageTransfersCmd listImageTransfersCmd() {
        ListImageTransfersCmd cmd = mock(ListImageTransfersCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getBackupId()).thenReturn(null);
        return cmd;
    }

    private BackupVO backup(long backupId, long vmId, long hostId) {
        BackupVO backup = new BackupVO();
        ReflectionTestUtils.setField(backup, "id", backupId);
        backup.setVmId(vmId);
        backup.setHostId(hostId);
        backup.setAccountId(201L);
        backup.setDomainId(301L);
        backup.setZoneId(101L);
        return backup;
    }

    private ImageTransferVO nbdUploadTransfer() {
        ImageTransferVO transfer = new ImageTransferVO("transfer-uuid", null, VOLUME_ID, HOST_ID, "transfer-uuid",
                ImageTransfer.Phase.transferring, ImageTransfer.Direction.upload, 201L, 301L, 101L);
        ReflectionTestUtils.setField(transfer, "id", 701L);
        return transfer;
    }
}
