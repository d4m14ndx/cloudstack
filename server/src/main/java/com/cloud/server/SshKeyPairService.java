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

import java.util.List;

import org.apache.cloudstack.api.command.user.ssh.CreateSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.DeleteSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.ListSSHKeyPairsCmd;
import org.apache.cloudstack.api.command.user.ssh.RegisterSSHKeyPairCmd;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.SSHKeyPair;
import com.cloud.utils.Pair;

/**
 * SSH keypair management — generate, register, list, and delete account-owned
 * SSH keypairs used to inject public keys into newly deployed instances.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code ManagementServerImpl} retains the
 * {@code registerSSHKeyPair} orchestration (along with the small helpers
 * {@code checkForKeyByName}, {@code checkForKeyByPublicKey},
 * {@code getPublicKeyFromKeyKeyMaterial}, and {@code getOwner}) so that
 * existing {@code @Spy} tests on those methods continue to work — those
 * helpers now delegate here for their non-spied DAO logic. The
 * {@code createSSHKeyPair}, {@code deleteSSHKeyPair}, and
 * {@code listSSHKeyPairs} entry points delegate in full.
 */
public interface SshKeyPairService {

    /**
     * Generate a fresh SSH keypair for the caller-resolved owner, persist
     * the public half, and return the freshly created {@link SSHKeyPair}
     * (with the private key set transiently on the returned VO so it can be
     * shown once to the caller).
     */
    SSHKeyPair createSshKeyPair(CreateSSHKeyPairCmd cmd);

    /**
     * Look up an account-owned keypair by name and remove it, also clearing
     * any annotations attached to the keypair. Falls back to a removed-
     * account lookup for admins when the owner cannot be resolved through
     * the normal {@code finalizeOwner} path.
     */
    boolean deleteSshKeyPair(DeleteSSHKeyPairCmd cmd);

    /**
     * Search account-visible keypairs with the usual ACL-aware paging,
     * narrowing by id, name, fingerprint, or keyword as supplied by the
     * command.
     */
    Pair<List<? extends SSHKeyPair>, Integer> listSshKeyPairs(ListSSHKeyPairsCmd cmd);

    /**
     * Reject the register request when an SSH keypair with the same
     * {@code name} already exists for the resolved owner.
     */
    void checkForExistingKeyByName(RegisterSSHKeyPairCmd cmd, Account owner)
            throws InvalidParameterValueException;

    /**
     * Reject the register request when an SSH keypair with the same public
     * key material already exists for the resolved owner. The caller is
     * expected to have already normalised the raw command input via
     * {@link #extractPublicKey(String)}.
     */
    void checkForExistingKeyByPublicKey(Account owner, String normalisedPublicKey)
            throws InvalidParameterValueException;

    /**
     * Extract the SSH2 public key from raw input, throwing
     * {@link InvalidParameterValueException} when the supplied material is
     * not parseable.
     */
    String extractPublicKey(String rawKeyMaterial) throws InvalidParameterValueException;

    /**
     * Compute the standard SSH key fingerprint for a normalised public key.
     */
    String computeFingerprint(String publicKey);

    /**
     * Persist a new keypair row for the supplied owner. The private key is
     * stored transiently on the returned VO and is NOT written to the
     * database — callers needing to surface it to the user must read it
     * from the returned object before discarding the reference.
     */
    SSHKeyPair saveSshKeyPair(String name, String fingerprint, String publicKey,
                              String privateKey, Account owner);
}
