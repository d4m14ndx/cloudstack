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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.vm.dao.InstanceGroupDao;
import com.cloud.vm.dao.InstanceGroupVMMapDao;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmGroupServiceImplTest {

    @Mock private AccountManager accountManager;
    @Mock private AccountDao accountDao;
    @Mock private UserVmDao vmDao;
    @Mock private InstanceGroupDao vmGroupDao;
    @Mock private InstanceGroupVMMapDao groupVmMapDao;
    @Mock private AnnotationDao annotationDao;

    @InjectMocks
    private VmGroupServiceImpl service;

    private static final long ACCOUNT_ID = 42L;
    private static final long GROUP_ID = 7L;
    private static final long VM_ID = 100L;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO();
        account.setId(ACCOUNT_ID);
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(account);
    }

    @Test
    public void createGroupReturnsExistingWhenNameAlreadyPersisted() {
        InstanceGroupVO existing = new InstanceGroupVO("mygroup", ACCOUNT_ID);
        when(vmGroupDao.findByAccountAndName(ACCOUNT_ID, "mygroup")).thenReturn(existing);

        InstanceGroupVO result = service.createVmGroup("mygroup", ACCOUNT_ID);

        assertEquals(existing, result);
        verify(vmGroupDao, never()).persist(any());
        verify(accountDao).releaseFromLockTable(ACCOUNT_ID);
    }

    @Test
    public void createGroupPersistsNewWhenNameDoesNotExist() {
        when(vmGroupDao.findByAccountAndName(ACCOUNT_ID, "newgroup")).thenReturn(null);
        when(vmGroupDao.persist(any(InstanceGroupVO.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InstanceGroupVO result = service.createVmGroup("newgroup", ACCOUNT_ID);

        assertNotNull(result);
        assertEquals("newgroup", result.getName());
        verify(vmGroupDao, times(1)).persist(any(InstanceGroupVO.class));
        verify(accountDao).releaseFromLockTable(ACCOUNT_ID);
    }

    @Test
    public void createGroupReturnsNullWhenAccountLockFails() {
        when(accountDao.acquireInLockTable(ACCOUNT_ID)).thenReturn(null);

        InstanceGroupVO result = service.createVmGroup("anything", ACCOUNT_ID);

        assertNull(result);
        verify(vmGroupDao, never()).persist(any());
        // lock failed so release is NOT called
        verify(accountDao, never()).releaseFromLockTable(ACCOUNT_ID);
    }

    @Test
    public void deleteVmGroupByIdRemovesMappingsAndGroup() {
        InstanceGroupVO group = new InstanceGroupVO("g", ACCOUNT_ID);
        when(vmGroupDao.findById(GROUP_ID)).thenReturn(group);
        when(groupVmMapDao.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(vmGroupDao.remove(GROUP_ID)).thenReturn(true);

        boolean result = service.deleteVmGroup(GROUP_ID);

        assertTrue(result);
        verify(annotationDao).removeByEntityType(anyString(), any());
        verify(vmGroupDao).remove(GROUP_ID);
    }

    @Test
    public void deleteVmGroupByIdReturnsFalseWhenDaoFails() {
        InstanceGroupVO group = new InstanceGroupVO("g", ACCOUNT_ID);
        when(vmGroupDao.findById(GROUP_ID)).thenReturn(group);
        when(groupVmMapDao.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(vmGroupDao.remove(GROUP_ID)).thenReturn(false);

        assertFalse(service.deleteVmGroup(GROUP_ID));
    }

    @Test
    public void getGroupForVmReturnsFirstGroupWhenAssigned() {
        InstanceGroupVMMapVO map = new InstanceGroupVMMapVO(GROUP_ID, VM_ID);
        when(groupVmMapDao.listByInstanceId(VM_ID)).thenReturn(List.of(map));
        InstanceGroupVO group = new InstanceGroupVO("g", ACCOUNT_ID);
        when(vmGroupDao.findById(GROUP_ID)).thenReturn(group);

        assertEquals(group, service.getGroupForVm(VM_ID));
    }

    @Test
    public void getGroupForVmReturnsNullWhenUnassigned() {
        when(groupVmMapDao.listByInstanceId(VM_ID)).thenReturn(Collections.emptyList());
        assertNull(service.getGroupForVm(VM_ID));
    }

    @Test
    public void getGroupForVmReturnsNullOnDaoException() {
        when(groupVmMapDao.listByInstanceId(VM_ID)).thenThrow(new RuntimeException("boom"));
        assertNull(service.getGroupForVm(VM_ID));
    }

    @Test
    public void removeInstanceFromInstanceGroupExpungesAllMappings() {
        InstanceGroupVMMapVO map1 = new InstanceGroupVMMapVO(GROUP_ID, VM_ID);
        when(groupVmMapDao.listByInstanceId(VM_ID)).thenReturn(List.of(map1));
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchCriteria<InstanceGroupVMMapVO> sc =
                org.mockito.Mockito.mock(com.cloud.utils.db.SearchCriteria.class);
        when(groupVmMapDao.createSearchCriteria()).thenReturn(sc);

        service.removeInstanceFromInstanceGroup(VM_ID);

        verify(groupVmMapDao).expunge(sc);
    }

    @Test
    public void removeInstanceFromInstanceGroupSwallowsExceptionsToBeRobust() {
        when(groupVmMapDao.listByInstanceId(VM_ID)).thenThrow(new RuntimeException("boom"));
        // Should not throw — VM cleanup must not fail on a transient DAO error.
        service.removeInstanceFromInstanceGroup(VM_ID);
    }

    @Test
    public void addInstanceToGroupFailsWhenGroupCannotBeCreated() {
        UserVmVO vm = new UserVmVO();
        org.springframework.test.util.ReflectionTestUtils.setField(vm, "accountId", ACCOUNT_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vmGroupDao.findByAccountAndName(eq(ACCOUNT_ID), eq("group"))).thenReturn(null);
        // Force createVmGroup to return null by failing the account lock
        when(accountDao.acquireInLockTable(anyLong())).thenReturn(null);

        assertFalse(service.addInstanceToGroup(VM_ID, "group"));
        verify(groupVmMapDao, never()).persist(any());
    }
}
