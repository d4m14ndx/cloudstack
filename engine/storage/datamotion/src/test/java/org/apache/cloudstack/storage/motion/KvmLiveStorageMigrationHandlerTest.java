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

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
}
