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
package org.apache.cloudstack.storage.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.host.HostVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

@RunWith(MockitoJUnitRunner.class)
public class KvmNonLiveStorageMigrationHandlerTest {

    @Mock
    private AgentManager agentManager;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VolumeDetailsDao volumeDetailsDao;
    @Mock
    private VolumeService volumeService;
    @Mock
    private VolumeInfo srcVolumeInfo;
    @Mock
    private VolumeInfo destVolumeInfo;
    @Mock
    private DataStore srcDataStore;
    @Mock
    private DataStore destDataStore;
    @Mock
    private PrimaryDataStoreDriver destDataStoreDriver;
    @Mock
    private VirtualMachine virtualMachine;

    @InjectMocks
    private KvmNonLiveStorageMigrationHandler handler;

    @Test(expected = CloudRuntimeException.class)
    public void checkAvailableForMigrationRejectsRunningVm() {
        when(virtualMachine.getState()).thenReturn(VirtualMachine.State.Running);

        handler.checkAvailableForMigration(virtualMachine);
    }

    @Test
    public void migrateVolumeForKVMGrantsAccessSendsCommandAndCleansUp() throws Exception {
        HostVO host = Mockito.mock(HostVO.class);
        when(host.getId()).thenReturn(10L);
        configureVolume(srcVolumeInfo, srcDataStore, 1L, 101L);
        configureVolume(destVolumeInfo, destDataStore, 2L, 202L);
        when(destDataStore.getDriver()).thenReturn(destDataStoreDriver);

        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool = Mockito.mock(StoragePoolVO.class);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool);
        when(storagePoolDao.findById(2L)).thenReturn(destPool);
        when(srcPool.isManaged()).thenReturn(false);
        when(destPool.isManaged()).thenReturn(true);
        when(destPool.getHostAddress()).thenReturn("10.1.1.20");
        when(destPool.getPort()).thenReturn(3260);

        VolumeVO destVolumeVO = Mockito.mock(VolumeVO.class);
        when(destVolumeVO.getPoolType()).thenReturn(Storage.StoragePoolType.Iscsi);
        when(destVolumeVO.get_iScsiName()).thenReturn("iqn.dest");
        when(destVolumeVO.getSize()).thenReturn(1024L);
        when(volumeDao.findById(202L)).thenReturn(destVolumeVO);
        when(agentManager.send(eq(10L), any(MigrateVolumeCommand.class))).thenReturn(new MigrateVolumeAnswer(null, true, null, "new-volume-path"));

        String path = handler.migrateVolumeForKVM(srcVolumeInfo, destVolumeInfo, host, "migration failed");

        assertEquals("new-volume-path", path);
        verify(volumeService, Mockito.times(2)).grantAccess(srcVolumeInfo, host, srcDataStore);
        InOrder inOrder = inOrder(destDataStoreDriver, volumeService, agentManager);
        inOrder.verify(destDataStoreDriver).handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.MIGRATION);
        inOrder.verify(volumeService).grantAccess(destVolumeInfo, host, destDataStore);
        inOrder.verify(agentManager).send(eq(10L), any(MigrateVolumeCommand.class));
        inOrder.verify(volumeService).revokeAccess(srcVolumeInfo, host, srcDataStore);
        inOrder.verify(volumeService).revokeAccess(destVolumeInfo, host, destDataStore);
        inOrder.verify(destDataStoreDriver).handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.NO_MIGRATION);
    }

    @Test
    public void checkAvailableForMigrationAllowsNullVm() {
        // should not throw
        handler.checkAvailableForMigration(null);
    }

    @Test
    public void checkAvailableForMigrationAllowsStoppedVm() {
        when(virtualMachine.getState()).thenReturn(VirtualMachine.State.Stopped);
        // should not throw
        handler.checkAvailableForMigration(virtualMachine);
    }

    @Test
    public void checkAvailableForMigrationAllowsMigratingVm() {
        when(virtualMachine.getState()).thenReturn(VirtualMachine.State.Migrating);
        // should not throw
        handler.checkAvailableForMigration(virtualMachine);
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkAvailableForMigrationRejectsStartingVm() {
        when(virtualMachine.getState()).thenReturn(VirtualMachine.State.Starting);
        handler.checkAvailableForMigration(virtualMachine);
    }

    @Test
    public void updateVolumePathSetsPathAndUpdatesDao() {
        VolumeVO volumeVO = Mockito.mock(VolumeVO.class);
        when(volumeDao.findById(101L)).thenReturn(volumeVO);

        handler.updateVolumePath(101L, "/new/path");

        verify(volumeVO).setPath("/new/path");
        verify(volumeDao).update(101L, volumeVO);
    }

    @Test
    public void getVolumePropertyReturnsNullWhenDetailNotFound() {
        when(volumeDetailsDao.findDetail(100L, "key")).thenReturn(null);

        String result = handler.getVolumeProperty(100L, "key");

        assertNull(result);
    }

    @Test
    public void getVolumePropertyReturnsValueWhenDetailFound() {
        VolumeDetailVO detail = Mockito.mock(VolumeDetailVO.class);
        when(volumeDetailsDao.findDetail(100L, "key")).thenReturn(detail);
        when(detail.getValue()).thenReturn("myvalue");

        String result = handler.getVolumeProperty(100L, "key");

        assertEquals("myvalue", result);
    }

    @Test
    public void updatePathFromScsiNameSetsPathWhenScsiNameIsPresent() {
        VolumeVO volumeVO = Mockito.mock(VolumeVO.class);
        when(volumeVO.get_iScsiName()).thenReturn("iqn.2024-01.test");
        when(volumeVO.getId()).thenReturn(200L);

        handler.updatePathFromScsiName(volumeVO);

        verify(volumeVO).setPath("iqn.2024-01.test");
        verify(volumeDao).update(200L, volumeVO);
    }

    @Test
    public void updatePathFromScsiNameSkipsUpdateWhenScsiNameIsNull() {
        VolumeVO volumeVO = Mockito.mock(VolumeVO.class);
        when(volumeVO.get_iScsiName()).thenReturn(null);

        handler.updatePathFromScsiName(volumeVO);

        verify(volumeDao, never()).update(eq(200L), any());
    }

    private void configureVolume(VolumeInfo volumeInfo, DataStore dataStore, long poolId, long volumeId) {
        when(volumeInfo.getPoolId()).thenReturn(poolId);
        when(volumeInfo.getId()).thenReturn(volumeId);
        when(volumeInfo.getDataStore()).thenReturn(dataStore);
        when(volumeInfo.getTO()).thenReturn(new VolumeObjectTO());
    }
}
