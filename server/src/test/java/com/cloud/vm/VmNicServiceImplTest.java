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
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;

/**
 * Focused unit tests for the helpers in {@link VmNicServiceImpl} that don't
 * require setting up the full chain of mocks the higher-level orchestration
 * methods need. Coverage for the orchestration methods themselves comes from
 * the existing UserVmManagerImplTest (via the delegating wrappers), which
 * exercises this service through dependency injection.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmNicServiceImplTest {

    @Mock private NetworkModel networkModel;

    @InjectMocks
    private VmNicServiceImpl service;

    @Test
    public void validateOrReplaceMacReturnsValidUnchanged() throws InsufficientAddressCapacityException {
        NetworkVO network = mockNetwork(42L);
        String result = service.validateOrReplaceMacAddress("aa:bb:cc:dd:ee:ff", network);
        assertEquals("aa:bb:cc:dd:ee:ff", result);
        verify(networkModel, times(0)).getNextAvailableMacAddressInNetwork(anyLong());
    }

    @Test
    public void validateOrReplaceMacReplacesInvalid() throws InsufficientAddressCapacityException {
        NetworkVO network = mockNetwork(42L);
        when(networkModel.getNextAvailableMacAddressInNetwork(42L)).thenReturn("00:11:22:33:44:55");

        String result = service.validateOrReplaceMacAddress("garbage", network);

        assertEquals("00:11:22:33:44:55", result);
    }

    @Test
    public void validateOrReplaceMacReplacesNull() throws InsufficientAddressCapacityException {
        NetworkVO network = mockNetwork(42L);
        when(networkModel.getNextAvailableMacAddressInNetwork(42L)).thenReturn("aa:00:00:00:00:01");

        assertEquals("aa:00:00:00:00:01", service.validateOrReplaceMacAddress(null, network));
    }

    @Test
    public void validateOrReplaceMacWrapsCapacityException() throws InsufficientAddressCapacityException {
        NetworkVO network = mockNetwork(42L);
        when(networkModel.getNextAvailableMacAddressInNetwork(42L))
                .thenThrow(new InsufficientAddressCapacityException("no MAC", null, 1L));

        try {
            service.validateOrReplaceMacAddress("nope", network);
            org.junit.Assert.fail("should throw CloudRuntimeException");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void setNicAsDefaultMarksDefaultWhenNoneExists() {
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        when(vm.getId()).thenReturn(7L);
        when(networkModel.getDefaultNic(7L)).thenReturn(null);

        NicProfile profile = new NicProfile();
        service.setNicAsDefaultIfNeeded(vm, profile);

        org.junit.Assert.assertTrue("profile should now be marked default", profile.isDefaultNic());
    }

    @Test
    public void setNicAsDefaultLeavesAloneWhenDefaultExists() {
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        when(vm.getId()).thenReturn(7L);
        when(networkModel.getDefaultNic(7L)).thenReturn(org.mockito.Mockito.mock(NicVO.class));

        NicProfile profile = new NicProfile();
        service.setNicAsDefaultIfNeeded(vm, profile);

        org.junit.Assert.assertFalse(profile.isDefaultNic());
    }

    private NetworkVO mockNetwork(long id) {
        NetworkVO n = org.mockito.Mockito.mock(NetworkVO.class);
        org.mockito.Mockito.lenient().when(n.getId()).thenReturn(id);
        return n;
    }
}
