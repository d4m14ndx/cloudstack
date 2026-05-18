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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.dao.AccountDao;
import com.cloud.vm.VMInstanceVO;

@RunWith(MockitoJUnitRunner.class)
public class AccountAccessServiceImplTest {
    private static final long ACCOUNT_ID = 42L;
    private static final long OTHER_ACCOUNT_ID = 43L;
    private static final long DOMAIN_ID = 7L;
    private static final long ZONE_ID = 11L;

    private AccountAccessServiceImpl service;

    @Mock
    private AccountDao accountDao;
    @Mock
    private DomainManager domainManager;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private SecurityChecker securityChecker;
    @Mock
    private Account caller;
    @Mock
    private Domain domain;
    @Mock
    private DataCenterVO dataCenter;

    @Before
    public void setUp() {
        service = Mockito.spy(new AccountAccessServiceImpl());
        ReflectionTestUtils.setField(service, "accountDao", accountDao);
        ReflectionTestUtils.setField(service, "domainManager", domainManager);
        ReflectionTestUtils.setField(service, "dataCenterDao", dataCenterDao);
        service.setSecurityCheckers(Collections.singletonList(securityChecker));
    }

    @Test
    public void isRootAdmin_returnsTrueWhenCheckerGrantsSystemCapability() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.NORMAL, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(securityChecker.checkAccess(Mockito.eq(account), Mockito.isNull(ControlledEntity.class),
                Mockito.isNull(AccessType.class), Mockito.eq("SystemCapability"))).thenReturn(true);

        assertTrue(service.isRootAdmin(ACCOUNT_ID));
    }

    @Test
    public void isRootAdmin_returnsFalseWhenAccountMissing() {
        assertFalse(service.isRootAdmin(ACCOUNT_ID));
    }

    @Test
    public void isDomainAdmin_returnsTrueWhenCheckerGrantsDomainCapability() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.NORMAL, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(securityChecker.checkAccess(Mockito.eq(account), Mockito.isNull(ControlledEntity.class),
                Mockito.isNull(AccessType.class), Mockito.eq("DomainCapability"))).thenReturn(true);

        assertTrue(service.isDomainAdmin(ACCOUNT_ID));
    }

    @Test
    public void isResourceDomainAdmin_returnsTrueWhenCheckerGrantsDomainResourceCapability() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.NORMAL, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(securityChecker.checkAccess(Mockito.eq(account), Mockito.isNull(ControlledEntity.class),
                Mockito.isNull(AccessType.class), Mockito.eq("DomainResourceCapability"))).thenReturn(true);

        assertTrue(service.isResourceDomainAdmin(ACCOUNT_ID));
    }

    @Test
    public void isAdmin_returnsTrueForReadOnlyAdmin() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.READ_ONLY_ADMIN, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertTrue(service.isAdmin(ACCOUNT_ID));
    }

    @Test
    public void isInternalAccount_returnsTrueForRootAdminOrAdminType() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.ADMIN, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertTrue(service.isInternalAccount(ACCOUNT_ID));
    }

    @Test
    public void checkAccessDomain_returnsWhenCheckerAllows() {
        Mockito.when(securityChecker.checkAccess(caller, domain)).thenReturn(true);

        service.checkAccess(caller, domain);
    }

    @Test
    public void checkAccessDomain_throwsWhenNoCheckerAllows() {
        assertThrows(PermissionDeniedException.class, () -> service.checkAccess(caller, domain));
    }

    @Test
    public void checkAccessControlledEntities_rejectsDifferentOwnersWhenSameOwnerRequired() {
        VMInstanceVO firstVm = vm(ACCOUNT_ID, DOMAIN_ID);
        VMInstanceVO secondVm = vm(OTHER_ACCOUNT_ID, DOMAIN_ID);

        assertThrows(PermissionDeniedException.class,
                () -> service.checkAccess(caller, AccessType.UseEntry, true, firstVm, secondVm));
    }

    @Test
    public void checkAccessControlledEntities_skipsCheckerForSystemAccount() {
        AccountVO systemAccount = account(Account.ACCOUNT_ID_SYSTEM, Account.Type.ADMIN, DOMAIN_ID);

        service.checkAccess(systemAccount, AccessType.UseEntry, true, vm(ACCOUNT_ID, DOMAIN_ID));

        Mockito.verifyNoInteractions(securityChecker);
    }

    @Test
    public void checkAccessControlledEntities_checksDomainWhenEntityHasDomain() {
        AccountVO callerAccount = account(ACCOUNT_ID, Account.Type.NORMAL, DOMAIN_ID);
        VMInstanceVO vm = vm(ACCOUNT_ID, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(callerAccount);
        Mockito.when(securityChecker.checkAccess(callerAccount, vm, AccessType.UseEntry, null)).thenReturn(true);
        Mockito.when(domainManager.getDomain(DOMAIN_ID)).thenReturn(domain);

        service.checkAccess(callerAccount, AccessType.UseEntry, true, vm);

        Mockito.verify(securityChecker).checkAccess(callerAccount, domain);
    }

    @Test
    public void checkAccessAndSpecifyAuthority_returnsPrivateZoneForResourceDomainAdmin() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.NORMAL, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(securityChecker.checkAccess(Mockito.eq(account), Mockito.isNull(ControlledEntity.class),
                Mockito.isNull(AccessType.class), Mockito.eq("DomainResourceCapability"))).thenReturn(true);
        Mockito.when(dataCenterDao.findZonesByDomainId(DOMAIN_ID)).thenReturn(Collections.singletonList(dataCenter));
        Mockito.when(dataCenter.getId()).thenReturn(ZONE_ID);

        assertEquals(Long.valueOf(ZONE_ID), service.checkAccessAndSpecifyAuthority(account, null));
    }

    @Test
    public void checkAccessAndSpecifyAuthority_rejectsDifferentZoneForResourceDomainAdmin() {
        AccountVO account = account(ACCOUNT_ID, Account.Type.NORMAL, DOMAIN_ID);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(securityChecker.checkAccess(Mockito.eq(account), Mockito.isNull(ControlledEntity.class),
                Mockito.isNull(AccessType.class), Mockito.eq("DomainResourceCapability"))).thenReturn(true);
        Mockito.when(dataCenterDao.findZonesByDomainId(DOMAIN_ID)).thenReturn(Collections.singletonList(dataCenter));
        Mockito.when(dataCenter.getId()).thenReturn(ZONE_ID);

        assertThrows(PermissionDeniedException.class,
                () -> service.checkAccessAndSpecifyAuthority(account, ZONE_ID + 1));
    }

    @Test
    public void validateAccountHasAccessToResource_delegatesControlledEntityToCheckAccess() {
        VMInstanceVO vm = vm(ACCOUNT_ID, DOMAIN_ID);
        Mockito.doNothing().when(service).checkAccess(caller, AccessType.UseEntry, true, vm);

        service.validateAccountHasAccessToResource(caller, AccessType.UseEntry, vm);

        Mockito.verify(service).checkAccess(caller, AccessType.UseEntry, true, vm);
    }

    private AccountVO account(long id, Account.Type type, long domainId) {
        AccountVO account = new AccountVO();
        account.setId(id);
        account.setType(type);
        account.setDomainId(domainId);
        return account;
    }

    private VMInstanceVO vm(long accountId, long domainId) {
        return new VMInstanceVO(1L, 1L, "vm", "i-1", null, 1L, null, 1L, domainId, accountId, 1L, false);
    }
}
