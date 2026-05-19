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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmDeviceBusInfoServiceImplTest {

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private UserVmVO vm;

    @InjectMocks
    private VmDeviceBusInfoServiceImpl service;

    @Test
    public void persistDeviceBusInfoDoesNotSaveWhenControllerIsNull() {
        when(vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER)).thenReturn(null);

        service.persistDeviceBusInfo(vm, null);

        verify(vm, never()).setDetail(eq(VmDetailConstants.ROOT_DISK_CONTROLLER), any());
        verify(userVmDao, never()).saveDetails(any(UserVmVO.class));
    }

    @Test
    public void persistDeviceBusInfoDoesNotSaveWhenControllerIsEmpty() {
        when(vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER)).thenReturn(null);

        service.persistDeviceBusInfo(vm, "");

        verify(vm, never()).setDetail(eq(VmDetailConstants.ROOT_DISK_CONTROLLER), any());
        verify(userVmDao, never()).saveDetails(any(UserVmVO.class));
    }

    @Test
    public void persistDeviceBusInfoDoesNotOverwriteExistingController() {
        when(vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER)).thenReturn("existing");

        service.persistDeviceBusInfo(vm, "lsilogic");

        verify(vm, never()).setDetail(eq(VmDetailConstants.ROOT_DISK_CONTROLLER), any());
        verify(userVmDao, never()).saveDetails(any(UserVmVO.class));
    }

    @Test
    public void persistDeviceBusInfoSetsAndSavesMissingController() {
        when(vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER)).thenReturn(null);

        service.persistDeviceBusInfo(vm, "lsilogic");

        verify(vm).setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, "lsilogic");
        verify(userVmDao).saveDetails(vm);
    }
}
