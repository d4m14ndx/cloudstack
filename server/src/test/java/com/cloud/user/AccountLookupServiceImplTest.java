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
package com.cloud.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;

@RunWith(MockitoJUnitRunner.class)
public class AccountLookupServiceImplTest {

    @Mock private AccountDao accountDao;
    @Mock private UserDao userDao;
    @Mock private UserAccountDao userAccountDao;
    @Mock private UserDetailsDao userDetailsDao;

    @InjectMocks
    private AccountLookupServiceImpl service;

    // ---- getActiveAccountByName ----

    @Test
    public void getActiveAccountByNameNullAccountNameRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.getActiveAccountByName(null, 1L));
    }

    @Test
    public void getActiveAccountByNameNullDomainIdRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.getActiveAccountByName("acct", null));
    }

    @Test
    public void getActiveAccountByNameDelegatesToDao() {
        AccountVO acct = new AccountVO();
        when(accountDao.findActiveAccount("acct", 5L)).thenReturn(acct);

        Account result = service.getActiveAccountByName("acct", 5L);

        assertSame(acct, result);
    }

    // ---- getActiveUserAccount ----

    @Test
    public void getActiveUserAccountDelegatesToDao() {
        UserAccountVO ua = new UserAccountVO();
        when(userAccountDao.getUserAccount("alice", 5L)).thenReturn(ua);

        UserAccount result = service.getActiveUserAccount("alice", 5L);

        assertSame(ua, result);
    }

    // ---- getActiveUserAccountByEmail ----

    @Test
    public void getActiveUserAccountByEmailReturnsMappedList() {
        UserAccountVO ua1 = new UserAccountVO();
        UserAccountVO ua2 = new UserAccountVO();
        when(userAccountDao.getUserAccountByEmail("a@b.com", 3L))
                .thenReturn(List.of(ua1, ua2));

        List<UserAccount> result = service.getActiveUserAccountByEmail("a@b.com", 3L);

        assertEquals(2, result.size());
        assertSame(ua1, result.get(0));
        assertSame(ua2, result.get(1));
    }

    @Test
    public void getActiveUserAccountByEmailEmptyListReturnsEmpty() {
        when(userAccountDao.getUserAccountByEmail("none@b.com", 3L))
                .thenReturn(Collections.emptyList());

        List<UserAccount> result = service.getActiveUserAccountByEmail("none@b.com", 3L);

        assertTrue(result.isEmpty());
    }

    // ---- getActiveAccountById ----

    @Test
    public void getActiveAccountByIdDelegatesToDao() {
        AccountVO acct = new AccountVO();
        when(accountDao.findById(7L)).thenReturn(acct);

        Account result = service.getActiveAccountById(7L);

        assertSame(acct, result);
    }

    // ---- getAccount ----

    @Test
    public void getAccountIncludesRemoved() {
        AccountVO acct = new AccountVO();
        when(accountDao.findByIdIncludingRemoved(9L)).thenReturn(acct);

        Account result = service.getAccount(9L);

        assertSame(acct, result);
        verify(accountDao).findByIdIncludingRemoved(9L);
        verify(accountDao, never()).findById(anyLong());
    }

    // ---- getRoleType ----

    @Test
    public void getRoleTypeNullAccountReturnsUnknown() {
        assertEquals(RoleType.Unknown, service.getRoleType(null));
    }

    @Test
    public void getRoleTypeNormalAccountReturnsUser() {
        Account acct = new AccountVO();
        ((AccountVO) acct).setType(Account.Type.NORMAL);

        assertEquals(RoleType.User, service.getRoleType(acct));
    }

    @Test
    public void getRoleTypeAdminAccountReturnsAdmin() {
        AccountVO acct = new AccountVO();
        acct.setType(Account.Type.ADMIN);

        assertEquals(RoleType.Admin, service.getRoleType(acct));
    }

    @Test
    public void getRoleTypeDomainAdminAccountReturnsDomainAdmin() {
        AccountVO acct = new AccountVO();
        acct.setType(Account.Type.DOMAIN_ADMIN);

        assertEquals(RoleType.DomainAdmin, service.getRoleType(acct));
    }

    // ---- getActiveUser ----

    @Test
    public void getActiveUserDelegatesToDao() {
        UserVO user = new UserVO();
        when(userDao.findById(11L)).thenReturn(user);

        User result = service.getActiveUser(11L);

        assertSame(user, result);
    }

    // ---- getUserIncludingRemoved ----

    @Test
    public void getUserIncludingRemovedUsesRemovedAwareLookup() {
        UserVO user = new UserVO();
        when(userDao.findByIdIncludingRemoved(12L)).thenReturn(user);

        User result = service.getUserIncludingRemoved(12L);

        assertSame(user, result);
        verify(userDao).findByIdIncludingRemoved(12L);
        verify(userDao, never()).findById(anyLong());
    }

    // ---- getActiveUserByRegistrationToken ----

    @Test
    public void getActiveUserByRegistrationTokenDelegatesToDao() {
        UserVO user = new UserVO();
        when(userDao.findUserByRegistrationToken("tok-123")).thenReturn(user);

        User result = service.getActiveUserByRegistrationToken("tok-123");

        assertSame(user, result);
    }

    // ---- markUserRegistered ----

    @Test
    public void markUserRegisteredFlipsRegisteredFlag() {
        UserVO updater = new UserVO();
        when(userDao.createForUpdate()).thenReturn(updater);

        service.markUserRegistered(42L);

        ArgumentCaptor<UserVO> captor = ArgumentCaptor.forClass(UserVO.class);
        verify(userDao, times(1)).update(eq(42L), captor.capture());
        assertTrue(captor.getValue().isRegistered());
    }

    // ---- getUserAccountById ----

    @Test
    public void getUserAccountByIdNullWhenNoSuchRow() {
        when(userAccountDao.findById(99L)).thenReturn(null);

        UserAccount result = service.getUserAccountById(99L);

        assertNull(result);
        verify(userDetailsDao, never()).listDetailsKeyPairs(anyLong());
    }

    @Test
    public void getUserAccountByIdFoldsDetailsOntoVo() {
        UserAccountVO ua = new UserAccountVO();
        Map<String, String> details = Map.of("k1", "v1", "k2", "v2");
        when(userAccountDao.findById(50L)).thenReturn(ua);
        when(userDetailsDao.listDetailsKeyPairs(50L)).thenReturn(details);

        UserAccount result = service.getUserAccountById(50L);

        assertSame(ua, result);
        // verify details propagated onto VO
        assertEquals(details, ((UserAccountVO) result).getDetails());
    }

    @Test
    public void getUserAccountByIdDoesNotConsultDetailsDaoOnMiss() {
        when(userAccountDao.findById(123L)).thenReturn(null);

        service.getUserAccountById(123L);

        verify(userDetailsDao, never()).listDetailsKeyPairs(anyLong());
    }
}
