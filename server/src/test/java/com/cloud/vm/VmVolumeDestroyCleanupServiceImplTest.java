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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class VmVolumeDestroyCleanupServiceImplTest {

    private static final long VM_ID = 101L;
    private static final long VOLUME_ID = 202L;
    private static final long SECOND_VOLUME_ID = 303L;
    private static final long ACCOUNT_ID = 404L;
    private static final long USER_ID = 505L;

    @InjectMocks
    private VmVolumeDestroyCleanupServiceImpl service;

    @Mock
    private VolumeApiService volumeService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private User callerUser;
    @Mock
    private Account callerAccount;
    @Mock
    private UserVm userVm;
    @Mock
    private UserVmVO userVmVo;

    private CallContext outerContext;

    @Before
    public void setUp() {
        CallContext.unregisterAll();
        CallContext.init(entityManager);
        when(callerUser.getId()).thenReturn(USER_ID);
        when(callerAccount.getId()).thenReturn(ACCOUNT_ID);
        lenient().when(entityManager.findById(User.class, USER_ID)).thenReturn(callerUser);
        lenient().when(entityManager.findById(Account.class, ACCOUNT_ID)).thenReturn(callerAccount);
        lenient().when(userVm.getUuid()).thenReturn("vm-uuid");
        lenient().when(userVmVo.getUuid()).thenReturn("vm-uuid");
        outerContext = CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
        CallContext.init(null);
    }

    @Test
    public void detachVolumesFromVmRegistersVolumeContextAndCallsDetach() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        Volume detachedVolume = mock(Volume.class);
        when(volumeService.detachVolumeViaDestroyVM(VM_ID, VOLUME_ID)).thenAnswer(invocation -> {
            assertVolumeContext(volume);
            return detachedVolume;
        });

        service.detachVolumesFromVm(userVm, List.of(volume));

        verify(volumeService).detachVolumeViaDestroyVM(VM_ID, VOLUME_ID);
        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void detachVolumesFromVmUnregistersWhenDetachThrows() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        RuntimeException exception = new RuntimeException("detach failed");
        when(volumeService.detachVolumeViaDestroyVM(VM_ID, VOLUME_ID)).thenThrow(exception);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.detachVolumesFromVm(userVm, List.of(volume)));

        assertSame(exception, thrown);
        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void detachVolumesFromVmContinuesAcrossMultipleVolumes() {
        VolumeVO first = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        VolumeVO second = volume(SECOND_VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        when(volumeService.detachVolumeViaDestroyVM(eq(VM_ID), anyLong())).thenReturn(mock(Volume.class));

        service.detachVolumesFromVm(userVm, List.of(first, second));

        verify(volumeService).detachVolumeViaDestroyVM(VM_ID, VOLUME_ID);
        verify(volumeService).detachVolumeViaDestroyVM(VM_ID, SECOND_VOLUME_ID);
        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void detachVolumesFromVmToleratesNullDetachResult() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        when(volumeService.detachVolumeViaDestroyVM(VM_ID, VOLUME_ID)).thenReturn(null);

        service.detachVolumesFromVm(userVm, List.of(volume));

        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void deleteVolumesFromVmDelegatesToDestroyVolumeInContextForEachVolume() {
        VmVolumeDestroyCleanupServiceImpl spyService = spy(service);
        VolumeVO first = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        VolumeVO second = volume(SECOND_VOLUME_ID, VM_ID, Volume.Type.ROOT);
        doNothing().when(spyService).destroyVolumeInContext(any(UserVmVO.class), anyBoolean(), any(VolumeVO.class));

        spyService.deleteVolumesFromVm(userVmVo, List.of(first, second), true);

        verify(spyService).destroyVolumeInContext(userVmVo, true, first);
        verify(spyService).destroyVolumeInContext(userVmVo, true, second);
    }

    @Test
    public void destroyVolumeInContextRegistersVolumeContextAndCallsDestroy() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        Volume destroyedVolume = mock(Volume.class);
        when(volumeService.destroyVolume(VOLUME_ID, callerAccount, true, false)).thenAnswer(invocation -> {
            assertVolumeContext(volume);
            return destroyedVolume;
        });

        service.destroyVolumeInContext(userVmVo, true, volume);

        verify(volumeService).destroyVolume(VOLUME_ID, callerAccount, true, false);
        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void destroyVolumeInContextUnregistersWhenDestroyThrows() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        RuntimeException exception = new RuntimeException("destroy failed");
        when(volumeService.destroyVolume(VOLUME_ID, callerAccount, true, false)).thenThrow(exception);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.destroyVolumeInContext(userVmVo, true, volume));

        assertSame(exception, thrown);
        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void destroyVolumeInContextToleratesNullDestroyResult() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        when(volumeService.destroyVolume(VOLUME_ID, callerAccount, false, false)).thenReturn(null);

        service.destroyVolumeInContext(userVmVo, false, volume);

        assertSame(outerContext, CallContext.current());
    }

    @Test
    public void destroyVolumeInContextUsesCallerAccountFromOuterContext() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        when(volumeService.destroyVolume(VOLUME_ID, callerAccount, true, false)).thenReturn(mock(Volume.class));

        service.destroyVolumeInContext(userVmVo, true, volume);

        verify(volumeService).destroyVolume(VOLUME_ID, callerAccount, true, false);
    }

    @Test
    public void deleteVolumesFromVmPassesExpungeFlagThroughUnchanged() {
        VmVolumeDestroyCleanupServiceImpl spyService = spy(service);
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        doNothing().when(spyService).destroyVolumeInContext(any(UserVmVO.class), anyBoolean(), any(VolumeVO.class));

        spyService.deleteVolumesFromVm(userVmVo, List.of(volume), false);

        verify(spyService, times(1)).destroyVolumeInContext(userVmVo, false, volume);
    }

    private void assertVolumeContext(VolumeVO volume) {
        CallContext volumeContext = CallContext.current();
        assertEquals(ApiCommandResourceType.Volume, volumeContext.getEventResourceType());
        assertEquals(volume.getId(), volumeContext.getEventResourceId().longValue());
        assertEquals("Volume Type: " + volume.getVolumeType() + " Volume ID: " + volume.getUuid() + " Instance ID: vm-uuid",
                volumeContext.getEventDetails());
    }

    private VolumeVO volume(long id, Long instanceId, Volume.Type type) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(id);
        when(volume.getInstanceId()).thenReturn(instanceId);
        when(volume.getVolumeType()).thenReturn(type);
        when(volume.getUuid()).thenReturn("volume-" + id);
        assertNull(CallContext.current().getEventResourceId());
        return volume;
    }
}
