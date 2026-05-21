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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class KvmLiveStorageMigrationHandlerTest {

    @Mock
    private Host srcHost;
    @Mock
    private Host destHost;
    @Mock
    private VolumeInfo volumeInfo;
    @Mock
    private DataStore dataStore;
    @Mock
    private VirtualMachineTO vmTO;
    @Mock
    private AsyncCompletionCallback<CopyCommandResult> callback;
    @Mock
    private StorageSystemDataMotionStrategy context;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;

    @InjectMocks
    private KvmLiveStorageMigrationHandler handler;

    @Test
    public void handleRejectsNonKvmSourceHostAndCompletesCallback() {
        when(srcHost.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(vmTO.getId()).thenReturn(1L);

        try {
            handler.handle(Collections.singletonMap(volumeInfo, dataStore), vmTO, srcHost, destHost, callback, context);
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            verify(callback).complete(any(CopyCommandResult.class));
        }
    }

    @Test
    public void verifyLiveMigrationThrowsWhenSrcStoragePoolIsNull() {
        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(storagePoolDao.findById(1L)).thenReturn(null);

        try {
            handler.verifyLiveMigrationForKVM(Collections.singletonMap(volumeInfo, dataStore));
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("not associated with a storage pool"));
        }
    }

    @Test
    public void verifyLiveMigrationThrowsWhenDestStoragePoolIsNull() {
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(dataStore.getId()).thenReturn(2L);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool);
        when(storagePoolDao.findById(2L)).thenReturn(null);

        try {
            handler.verifyLiveMigrationForKVM(Collections.singletonMap(volumeInfo, dataStore));
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("Destination storage pool"));
        }
    }

    @Test
    public void verifyLiveMigrationThrowsWhenMigratingFromManagedStorageToDifferentPool() {
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool = Mockito.mock(StoragePoolVO.class);
        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(dataStore.getId()).thenReturn(2L);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool);
        when(storagePoolDao.findById(2L)).thenReturn(destPool);
        when(srcPool.isManaged()).thenReturn(true);
        when(srcPool.getId()).thenReturn(1L);
        when(destPool.getId()).thenReturn(2L);

        try {
            handler.verifyLiveMigrationForKVM(Collections.singletonMap(volumeInfo, dataStore));
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("Migrating a volume online with KVM from managed storage"));
        }
    }

    @Test
    public void verifyLiveMigrationThrowsWhenDestinationPoolTypesAreInconsistent() {
        VolumeInfo volumeInfo2 = Mockito.mock(VolumeInfo.class);
        DataStore dataStore2 = Mockito.mock(DataStore.class);
        StoragePoolVO srcPool1 = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool1 = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO srcPool2 = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool2 = Mockito.mock(StoragePoolVO.class);

        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(dataStore.getId()).thenReturn(10L);
        when(volumeInfo2.getPoolId()).thenReturn(2L);
        when(dataStore2.getId()).thenReturn(20L);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool1);
        when(storagePoolDao.findById(10L)).thenReturn(destPool1);
        when(storagePoolDao.findById(2L)).thenReturn(srcPool2);
        when(storagePoolDao.findById(20L)).thenReturn(destPool2);
        when(srcPool1.isManaged()).thenReturn(false);
        when(destPool1.isManaged()).thenReturn(false);
        when(srcPool2.isManaged()).thenReturn(false);
        when(destPool2.isManaged()).thenReturn(true);

        Map<VolumeInfo, DataStore> map = new HashMap<>();
        map.put(volumeInfo, dataStore);
        map.put(volumeInfo2, dataStore2);

        try {
            handler.verifyLiveMigrationForKVM(map);
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("either all managed or all not managed"));
        }
    }

    @Test
    public void verifyLiveMigrationSucceedsForUnmanagedPools() {
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool = Mockito.mock(StoragePoolVO.class);
        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(dataStore.getId()).thenReturn(2L);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool);
        when(storagePoolDao.findById(2L)).thenReturn(destPool);
        when(srcPool.isManaged()).thenReturn(false);
        when(destPool.isManaged()).thenReturn(false);

        // should not throw
        handler.verifyLiveMigrationForKVM(Collections.singletonMap(volumeInfo, dataStore));
    }

    @Test
    public void formatMigrationElementsAsJsonFormatsCorrectly() {
        String result = handler.formatMigrationElementsAsJsonToDisplayOnLog("volume", "vol1", "host1", "host2");
        assertEquals("{volume: \"vol1\", from: \"host1\", to:\"host2\"}", result);
    }

    @Test
    public void formatEntryOfVolumesAndStoragesFormatsCorrectly() {
        when(volumeInfo.getId()).thenReturn(100L);
        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(dataStore.getId()).thenReturn(2L);
        Map.Entry<VolumeInfo, DataStore> entry = Collections.singletonMap(volumeInfo, dataStore).entrySet().iterator().next();

        String result = handler.formatEntryOfVolumesAndStoragesAsJsonToDisplayOnLog(entry);
        assertTrue(result.contains("100"));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("2"));
    }

    @Test
    public void handleRejectsVmwareHostAndCompletesCallback() {
        when(srcHost.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(vmTO.getId()).thenReturn(2L);

        try {
            handler.handle(Collections.singletonMap(volumeInfo, dataStore), vmTO, srcHost, destHost, callback, context);
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            verify(callback).complete(any(CopyCommandResult.class));
        }
    }

    @Test
    public void verifyLiveMigrationSucceedsForManagedSrcAndSameDestPool() {
        StoragePoolVO srcPool = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO destPool = Mockito.mock(StoragePoolVO.class);
        when(volumeInfo.getPoolId()).thenReturn(1L);
        when(dataStore.getId()).thenReturn(1L);
        when(storagePoolDao.findById(1L)).thenReturn(srcPool).thenReturn(destPool);
        when(srcPool.isManaged()).thenReturn(true);
        when(srcPool.getId()).thenReturn(1L);
        when(destPool.getId()).thenReturn(1L);
        when(destPool.isManaged()).thenReturn(true);

        // should not throw when same pool (srcId == destId)
        handler.verifyLiveMigrationForKVM(Collections.singletonMap(volumeInfo, dataStore));
    }
}
