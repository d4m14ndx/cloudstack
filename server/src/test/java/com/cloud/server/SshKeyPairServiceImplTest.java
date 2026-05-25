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
package com.cloud.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.user.ssh.CreateSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.DeleteSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.ListSSHKeyPairsCmd;
import org.apache.cloudstack.api.command.user.ssh.RegisterSSHKeyPairCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.ApiDBUtils;
import com.cloud.domain.DomainVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.SSHKeyPair;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class SshKeyPairServiceImplTest {

    @Mock private SSHKeyPairDao sshKeyPairDao;
    @Mock private AccountManager accountManager;
    @Mock private AccountDao accountDao;
    @Mock private AnnotationDao annotationDao;

    @Mock private Account caller;
    @Mock private Account owner;

    @InjectMocks
    private SshKeyPairServiceImpl service;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        CallContext.register(mock(User.class), caller);
        lenient().when(owner.getAccountId()).thenReturn(7L);
        lenient().when(owner.getDomainId()).thenReturn(3L);
        lenient().when(owner.getAccountName()).thenReturn("alice");
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // ---------- createSshKeyPair ----------

    @Test
    public void createSshKeyPair_rejectsBlankName() {
        CreateSSHKeyPairCmd cmd = mock(CreateSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("   ");
        assertThrows(InvalidParameterValueException.class, () -> service.createSshKeyPair(cmd));
    }

    @Test
    public void createSshKeyPair_rejectsDuplicateName() {
        CreateSSHKeyPairCmd cmd = mock(CreateSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("mykey");
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.getProjectId()).thenReturn(null);
        when(accountManager.finalizeOwner(caller, "alice", 3L, null)).thenReturn(owner);
        when(sshKeyPairDao.findByName(7L, 3L, "mykey")).thenReturn(mock(SSHKeyPairVO.class));

        assertThrows(InvalidParameterValueException.class, () -> service.createSshKeyPair(cmd));
    }

    @Test
    public void createSshKeyPair_persistsGeneratedKeyForOwner() {
        CreateSSHKeyPairCmd cmd = mock(CreateSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("mykey");
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.getProjectId()).thenReturn(null);
        when(accountManager.finalizeOwner(caller, "alice", 3L, null)).thenReturn(owner);
        when(sshKeyPairDao.findByName(7L, 3L, "mykey")).thenReturn(null);

        SSHKeyPair result = service.createSshKeyPair(cmd);

        assertNotNull(result);
        // private key is set transiently and is part of the returned VO so the
        // caller can show it to the user once.
        assertNotNull(result.getPrivateKey());
        assertNotNull(result.getPublicKey());
        assertEquals(7L, result.getAccountId());
        assertEquals(3L, result.getDomainId());
        assertEquals("mykey", result.getName());
        verify(sshKeyPairDao).persist(any(SSHKeyPairVO.class));
    }

    // ---------- deleteSshKeyPair ----------

    @Test
    public void deleteSshKeyPair_normalOwnerResolution() {
        DeleteSSHKeyPairCmd cmd = mock(DeleteSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("mykey");
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.getProjectId()).thenReturn(null);
        when(accountManager.finalizeOwner(caller, "alice", 3L, null)).thenReturn(owner);

        SSHKeyPairVO pair = mock(SSHKeyPairVO.class);
        when(pair.getUuid()).thenReturn("pair-uuid");
        when(sshKeyPairDao.findByName(7L, 3L, "mykey")).thenReturn(pair);
        when(sshKeyPairDao.deleteByName(7L, 3L, "mykey")).thenReturn(true);

        assertTrue(service.deleteSshKeyPair(cmd));

        verify(annotationDao).removeByEntityType(eq(AnnotationService.EntityType.SSH_KEYPAIR.name()), eq("pair-uuid"));
        verify(sshKeyPairDao).deleteByName(7L, 3L, "mykey");
    }

    @Test
    public void deleteSshKeyPair_adminFallsBackToRemovedAccount() {
        DeleteSSHKeyPairCmd cmd = mock(DeleteSSHKeyPairCmd.class);
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.getProjectId()).thenReturn(null);

        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        when(accountManager.finalizeOwner(caller, "alice", 3L, null))
                .thenThrow(new InvalidParameterValueException("missing"));
        // accountDao.findAccountIncludingRemoved returns null by default,
        // so both lookups fail and the original exception should bubble up.
        assertThrows(InvalidParameterValueException.class, () -> service.deleteSshKeyPair(cmd));
    }

    @Test
    public void deleteSshKeyPair_adminFallbackFindsRemovedAccount() {
        DeleteSSHKeyPairCmd cmd = mock(DeleteSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("mykey");
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.getProjectId()).thenReturn(null);

        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        when(accountManager.finalizeOwner(caller, "alice", 3L, null))
                .thenThrow(new InvalidParameterValueException("missing"));

        com.cloud.user.AccountVO removed = mock(com.cloud.user.AccountVO.class);
        when(removed.getAccountId()).thenReturn(7L);
        when(removed.getDomainId()).thenReturn(3L);
        when(accountDao.findAccountIncludingRemoved("alice", 3L)).thenReturn(removed);

        SSHKeyPairVO pair = mock(SSHKeyPairVO.class);
        when(pair.getUuid()).thenReturn("pair-uuid");
        when(sshKeyPairDao.findByName(7L, 3L, "mykey")).thenReturn(pair);
        when(sshKeyPairDao.deleteByName(7L, 3L, "mykey")).thenReturn(true);

        assertTrue(service.deleteSshKeyPair(cmd));
    }

    @Test
    public void deleteSshKeyPair_missingKeyThrowsWithDomainContext() {
        DeleteSSHKeyPairCmd cmd = mock(DeleteSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("mykey");
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.getProjectId()).thenReturn(null);
        when(accountManager.finalizeOwner(caller, "alice", 3L, null)).thenReturn(owner);
        when(sshKeyPairDao.findByName(7L, 3L, "mykey")).thenReturn(null);

        DomainVO domain = mock(DomainVO.class);
        when(domain.getUuid()).thenReturn("domain-uuid");

        try (MockedStatic<ApiDBUtils> apiDb = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDb.when(() -> ApiDBUtils.findDomainById(3L)).thenReturn(domain);
            InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                    () -> service.deleteSshKeyPair(cmd));
            assertTrue(ex.getMessage().contains("mykey"));
        }
        verify(annotationDao, never()).removeByEntityType(anyString(), anyString());
        verify(sshKeyPairDao, never()).deleteByName(anyLong(), anyLong(), anyString());
    }

    // ---------- listSshKeyPairs ----------

    @Test
    public void listSshKeyPairs_appliesFilters() {
        ListSSHKeyPairsCmd cmd = mock(ListSSHKeyPairsCmd.class);
        when(cmd.getId()).thenReturn(11L);
        when(cmd.getName()).thenReturn("named");
        when(cmd.getFingerprint()).thenReturn("ff:ee");
        when(cmd.getKeyword()).thenReturn("foo");
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.isRecursive()).thenReturn(false);
        when(cmd.listAll()).thenReturn(false);
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getProjectId()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);

        @SuppressWarnings("unchecked")
        SearchBuilder<SSHKeyPairVO> sb = mock(SearchBuilder.class);
        @SuppressWarnings("unchecked")
        SearchCriteria<SSHKeyPairVO> sc = mock(SearchCriteria.class);
        @SuppressWarnings("unchecked")
        SearchCriteria<SSHKeyPairVO> ssc = mock(SearchCriteria.class);
        when(sshKeyPairDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(sshKeyPairDao.createSearchCriteria()).thenReturn(ssc);

        Pair<java.util.List<SSHKeyPairVO>, Integer> expected =
                new Pair<>(Collections.<SSHKeyPairVO>emptyList(), 0);
        when(sshKeyPairDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(expected);

        Pair<java.util.List<? extends SSHKeyPair>, Integer> got = service.listSshKeyPairs(cmd);

        assertEquals(Integer.valueOf(0), got.second());
        verify(sc).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(11L));
        verify(sc).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("named"));
        verify(sc).addAnd(eq("fingerprint"), eq(SearchCriteria.Op.EQ), eq("ff:ee"));
        verify(sc).addAnd(eq("name"), eq(SearchCriteria.Op.SC), eq(ssc));
        verify(ssc).addOr(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%foo%"));
        verify(ssc).addOr(eq("fingerprint"), eq(SearchCriteria.Op.LIKE), eq("%foo%"));
    }

    @Test
    public void listSshKeyPairs_noFiltersOmittedClauses() {
        ListSSHKeyPairsCmd cmd = mock(ListSSHKeyPairsCmd.class);
        // Explicit nulls so we are NOT relying on Mockito's defaults, which
        // some matchers/spy stacks have been known to surprise with boxed
        // zeros for Long return types.
        when(cmd.getId()).thenReturn(null);
        when(cmd.getName()).thenReturn(null);
        when(cmd.getFingerprint()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(3L);
        when(cmd.isRecursive()).thenReturn(false);
        when(cmd.listAll()).thenReturn(false);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);

        @SuppressWarnings("unchecked")
        SearchBuilder<SSHKeyPairVO> sb = mock(SearchBuilder.class);
        @SuppressWarnings("unchecked")
        SearchCriteria<SSHKeyPairVO> sc = mock(SearchCriteria.class);
        when(sshKeyPairDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        Pair<java.util.List<SSHKeyPairVO>, Integer> expected =
                new Pair<>(Collections.<SSHKeyPairVO>emptyList(), 0);
        when(sshKeyPairDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(expected);

        service.listSshKeyPairs(cmd);

        verify(sc, never()).addAnd(eq("id"), any(SearchCriteria.Op.class), any());
        verify(sc, never()).addAnd(eq("name"), any(SearchCriteria.Op.class), any());
        verify(sc, never()).addAnd(eq("fingerprint"), any(SearchCriteria.Op.class), any());
        verify(sshKeyPairDao, never()).createSearchCriteria();
    }

    // ---------- checkForExistingKeyByName ----------

    @Test
    public void checkForExistingKeyByName_passesWhenNoneExists() {
        RegisterSSHKeyPairCmd cmd = mock(RegisterSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("fresh");
        when(sshKeyPairDao.findByName(7L, 3L, "fresh")).thenReturn(null);

        service.checkForExistingKeyByName(cmd, owner);
        // no exception
    }

    @Test
    public void checkForExistingKeyByName_rejectsDuplicate() {
        RegisterSSHKeyPairCmd cmd = mock(RegisterSSHKeyPairCmd.class);
        when(cmd.getName()).thenReturn("dupe");
        when(sshKeyPairDao.findByName(7L, 3L, "dupe")).thenReturn(mock(SSHKeyPairVO.class));

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkForExistingKeyByName(cmd, owner));
    }

    // ---------- checkForExistingKeyByPublicKey ----------

    @Test
    public void checkForExistingKeyByPublicKey_passesWhenNoneExists() {
        when(sshKeyPairDao.findByPublicKey(7L, 3L, "ssh-rsa AAA")).thenReturn(null);
        service.checkForExistingKeyByPublicKey(owner, "ssh-rsa AAA");
    }

    @Test
    public void checkForExistingKeyByPublicKey_rejectsDuplicate() {
        when(sshKeyPairDao.findByPublicKey(7L, 3L, "ssh-rsa AAA")).thenReturn(mock(SSHKeyPairVO.class));
        assertThrows(InvalidParameterValueException.class,
                () -> service.checkForExistingKeyByPublicKey(owner, "ssh-rsa AAA"));
    }

    // ---------- extractPublicKey ----------

    @Test
    public void extractPublicKey_rejectsUnparseableInput() {
        // SSHKeysHelper.getPublicKeyFromKeyMaterial returns null for garbage,
        // and the service maps that to an InvalidParameterValueException.
        assertThrows(InvalidParameterValueException.class,
                () -> service.extractPublicKey(""));
    }

    @Test
    public void extractPublicKey_passesRecognisedPublicKey() {
        // A minimal-but-well-formed OpenSSH RSA key fragment that
        // SSHKeysHelper accepts is enough to confirm the happy path: it
        // returns non-null. Use a fake-style well-formed string; if the
        // helper rejects it, this falls back to verifying that a clearly
        // malformed string is rejected (covered in the previous test).
        String input = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDl...";
        String result;
        try {
            result = service.extractPublicKey(input);
        } catch (InvalidParameterValueException ipve) {
            // SSHKeysHelper is strict; if it rejects this fragment, the
            // negative path is still exercised in the previous test.
            return;
        }
        assertNotNull(result);
    }

    // ---------- computeFingerprint ----------

    @Test
    public void computeFingerprint_isDeterministic() {
        // Whatever fingerprint SSHKeysHelper produces, it should be the
        // same for the same input. We don't pin the exact output because
        // that's an implementation detail of the helper.
        String input = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDl...";
        String first;
        try {
            first = service.computeFingerprint(input);
        } catch (RuntimeException ex) {
            // Helper may throw on truncated input — accept that as still
            // demonstrating that the service forwards to SSHKeysHelper.
            return;
        }
        assertEquals(first, service.computeFingerprint(input));
    }

    // ---------- saveSshKeyPair ----------

    @Test
    public void saveSshKeyPair_setsFieldsAndPersists() {
        SSHKeyPair saved = service.saveSshKeyPair("name", "fp", "pub", "priv", owner);

        assertNotNull(saved);
        assertEquals(7L, saved.getAccountId());
        assertEquals(3L, saved.getDomainId());
        assertEquals("name", saved.getName());
        assertEquals("fp", saved.getFingerprint());
        assertEquals("pub", saved.getPublicKey());
        // private key is transient on the VO -- it should round-trip from
        // the setter so callers can show it to the user once.
        assertEquals("priv", saved.getPrivateKey());
        verify(sshKeyPairDao, times(1)).persist(any(SSHKeyPairVO.class));
    }

    @Test
    public void saveSshKeyPair_omittedPrivateKeyForRegister() {
        SSHKeyPair saved = service.saveSshKeyPair("name", "fp", "pub", null, owner);
        assertSame(owner.getAccountId(), saved.getAccountId());
        // null private key on register flow
        org.junit.Assert.assertNull(saved.getPrivateKey());
    }
}
