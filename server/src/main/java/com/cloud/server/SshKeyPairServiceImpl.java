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

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.user.ssh.CreateSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.DeleteSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.ListSSHKeyPairsCmd;
import org.apache.cloudstack.api.command.user.ssh.RegisterSSHKeyPairCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.domain.DomainVO;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.SSHKeyPair;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.ssh.SSHKeysHelper;

/**
 * @see SshKeyPairService
 */
@Component
public class SshKeyPairServiceImpl implements SshKeyPairService {

    @Inject
    private SSHKeyPairDao sshKeyPairDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private AccountDao accountDao;
    @Inject
    private AnnotationDao annotationDao;

    @Override
    public SSHKeyPair createSshKeyPair(final CreateSSHKeyPairCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        final String name = cmd.getName();

        if (StringUtils.isBlank(name)) {
            throw new InvalidParameterValueException(
                    "Please specify a valid name for the key pair. The key name can't be empty");
        }

        final Account owner = accountManager.finalizeOwner(caller, accountName, domainId, projectId);

        final SSHKeyPairVO s = sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (s != null) {
            throw new InvalidParameterValueException(
                    "A key pair with name '" + cmd.getName() + "' already exists.");
        }

        final SSHKeysHelper keys = new SSHKeysHelper(ManagementServerImpl.sshKeyLength.value());
        final String publicKey = keys.getPublicKey();
        final String fingerprint = keys.getPublicKeyFingerPrint();
        final String privateKey = keys.getPrivateKey();

        return saveSshKeyPair(name, fingerprint, publicKey, privateKey, owner);
    }

    @Override
    public boolean deleteSshKeyPair(final DeleteSSHKeyPairCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        Account owner = null;
        try {
            owner = accountManager.finalizeOwner(caller, accountName, domainId, projectId);
        } catch (InvalidParameterValueException ex) {
            if (caller.getType() == Account.Type.ADMIN && accountName != null && domainId != null) {
                owner = accountDao.findAccountIncludingRemoved(accountName, domainId);
            }
            if (owner == null) {
                throw ex;
            }
        }

        final SSHKeyPairVO s = sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (s == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "A key pair with name '" + cmd.getName() + "' does not exist for account " + owner.getAccountName() + " in specified domain id");
            final DomainVO domain = ApiDBUtils.findDomainById(owner.getDomainId());
            String domainUuid = String.valueOf(owner.getDomainId());
            if (domain != null) {
                domainUuid = domain.getUuid();
            }
            ex.addProxyObject(domainUuid, "domainId");
            throw ex;
        }
        annotationDao.removeByEntityType(AnnotationService.EntityType.SSH_KEYPAIR.name(), s.getUuid());

        return sshKeyPairDao.deleteByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
    }

    @Override
    public Pair<List<? extends SSHKeyPair>, Integer> listSshKeyPairs(final ListSSHKeyPairsCmd cmd) {
        final Long id = cmd.getId();
        final String name = cmd.getName();
        final String fingerPrint = cmd.getFingerprint();
        final String keyword = cmd.getKeyword();

        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> permittedAccounts = new ArrayList<>();

        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject =
                new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, null, cmd.getAccountName(), cmd.getProjectId(),
                permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        final SearchBuilder<SSHKeyPairVO> sb = sshKeyPairDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        final Filter searchFilter = new Filter(SSHKeyPairVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        final SearchCriteria<SSHKeyPairVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        if (fingerPrint != null) {
            sc.addAnd("fingerprint", SearchCriteria.Op.EQ, fingerPrint);
        }

        if (keyword != null) {
            final SearchCriteria<SSHKeyPairVO> ssc = sshKeyPairDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("fingerprint", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        final Pair<List<SSHKeyPairVO>, Integer> result = sshKeyPairDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public void checkForExistingKeyByName(final RegisterSSHKeyPairCmd cmd, final Account owner)
            throws InvalidParameterValueException {
        final SSHKeyPairVO existingPair = sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (existingPair != null) {
            throw new InvalidParameterValueException(
                    "A key pair with name '" + cmd.getName() + "' already exists for this account.");
        }
    }

    @Override
    public void checkForExistingKeyByPublicKey(final Account owner, final String normalisedPublicKey)
            throws InvalidParameterValueException {
        final SSHKeyPairVO existingPair = sshKeyPairDao.findByPublicKey(owner.getAccountId(), owner.getDomainId(), normalisedPublicKey);
        if (existingPair != null) {
            throw new InvalidParameterValueException(
                    "A key pair with key '" + normalisedPublicKey + "' already exists for this account.");
        }
    }

    @Override
    public String extractPublicKey(final String rawKeyMaterial) throws InvalidParameterValueException {
        final String publicKey = SSHKeysHelper.getPublicKeyFromKeyMaterial(rawKeyMaterial);
        if (publicKey == null) {
            throw new InvalidParameterValueException("Public key is invalid");
        }
        return publicKey;
    }

    @Override
    public String computeFingerprint(final String publicKey) {
        return SSHKeysHelper.getPublicKeyFingerprint(publicKey);
    }

    @Override
    public SSHKeyPair saveSshKeyPair(final String name, final String fingerprint, final String publicKey,
                                     final String privateKey, final Account owner) {
        final SSHKeyPairVO newPair = new SSHKeyPairVO();

        newPair.setAccountId(owner.getAccountId());
        newPair.setDomainId(owner.getDomainId());
        newPair.setName(name);
        newPair.setFingerprint(fingerprint);
        newPair.setPublicKey(publicKey);
        newPair.setPrivateKey(privateKey); // transient; not saved.

        sshKeyPairDao.persist(newPair);

        return newPair;
    }
}
