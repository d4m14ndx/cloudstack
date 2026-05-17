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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.host.UpdateHostPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.GetVMPasswordCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.DetailVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

/**
 * @see HostCredentialsService
 */
@Component
public class HostCredentialsServiceImpl implements HostCredentialsService {

    protected Logger logger = LogManager.getLogger(getClass());

    /**
     * Hypervisor types for which the management server stores agent
     * username/password credentials in {@code host_details}. KVM and
     * XenServer use this mechanism; other hypervisor types (VMware, Hyper-V)
     * either provide their own credential storage or never call into the
     * generic update path. Kept as a constant rather than a configurable
     * field so the slice has no implicit reliance on
     * {@link ManagementServerImpl#configure} ordering — the previous
     * implementation populated the list at startup.
     */
    private static final List<HypervisorType> SUPPORTED_HYPERVISORS =
            List.of(HypervisorType.KVM, HypervisorType.XenServer);

    @Inject
    private AccountManager accountManager;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private HostDetailsDao hostDetailsDao;
    @Inject
    private ClusterDao clusterDao;

    @Override
    public String getVMPassword(final GetVMPasswordCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final long vmId = cmd.getId();
        final UserVmVO vm = userVmDao.findById(vmId);

        if (vm == null) {
            throw new InvalidParameterValueException(String.format("No instance found with id [%s].", vmId));
        }

        accountManager.checkAccess(caller, null, true, vm);

        userVmDao.loadDetails(vm);
        final String password = vm.getDetail("Encrypted.Password");

        if (StringUtils.isEmpty(password)) {
            throw new InvalidParameterValueException(String.format("No password found for Instance [%s]. When the Instance's SSH keypair is changed, the current encrypted password is "
              + "removed due to inconsistency in the encryption, as the new SSH keypair is different from which the password was encrypted. To get a new password, it must be reseted.", vm));
        }

        return password;
    }

    @Override
    public boolean updateClusterPassword(final UpdateHostPasswordCmd command) {
        if (command.getClusterId() == null) {
            throw new InvalidParameterValueException("You should provide a cluster id.");
        }

        final ClusterVO cluster = clusterDao.findById(command.getClusterId());
        if (cluster == null || !SUPPORTED_HYPERVISORS.contains(cluster.getHypervisorType())) {
            throw new InvalidParameterValueException("This operation is not supported for this hypervisor type");
        }
        return updateHostsInCluster(command);
    }

    @Override
    public boolean updateHostPassword(final UpdateHostPasswordCmd cmd) {
        if (cmd.getHostId() == null) {
            throw new InvalidParameterValueException("You should provide an host id.");
        }

        final HostVO host = hostDao.findById(cmd.getHostId());

        if (host.getHypervisorType() == HypervisorType.XenServer) {
            throw new InvalidParameterValueException("Single host update is not supported by XenServer hypervisors. Please try again informing the Cluster ID.");
        }

        if (!SUPPORTED_HYPERVISORS.contains(host.getHypervisorType())) {
            throw new InvalidParameterValueException("This operation is not supported for this hypervisor type");
        }

        final String userNameWithoutSpaces = StringUtils.deleteWhitespace(cmd.getUsername());
        if (StringUtils.isBlank(userNameWithoutSpaces)) {
            throw new InvalidParameterValueException("Username should be non empty string");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Changing password for host {}", host);
                }
                // update password for this host
                final DetailVO nv = hostDetailsDao.findDetail(host.getId(), ApiConstants.USERNAME);
                if (nv == null) {
                    final DetailVO nvu = new DetailVO(host.getId(), ApiConstants.USERNAME, userNameWithoutSpaces);
                    hostDetailsDao.persist(nvu);
                    final DetailVO nvp = new DetailVO(host.getId(), ApiConstants.PASSWORD, DBEncryptionUtil.encrypt(cmd.getPassword()));
                    hostDetailsDao.persist(nvp);
                } else if (nv.getValue().equals(userNameWithoutSpaces)) {
                    final DetailVO nvp = hostDetailsDao.findDetail(host.getId(), ApiConstants.PASSWORD);
                    nvp.setValue(DBEncryptionUtil.encrypt(cmd.getPassword()));
                    hostDetailsDao.persist(nvp);
                } else {
                    // if one host in the cluster has diff username then
                    // rollback to maintain consistency
                    throw new InvalidParameterValueException("The username is not same for the hosts..");
                }
            }
        });
        return true;
    }

    /**
     * Apply the username/password update to every host in the cluster. Kept
     * package-private so it can be exercised directly from unit tests; the
     * old god class had this method as {@code private} and called it from
     * {@link #updateClusterPassword}.
     */
    private boolean updateHostsInCluster(final UpdateHostPasswordCmd command) {
        // get all the hosts in this cluster
        final List<Long> hostIds = hostDao.listIdsByClusterId(command.getClusterId());

        final String userNameWithoutSpaces = StringUtils.deleteWhitespace(command.getUsername());
        if (StringUtils.isBlank(userNameWithoutSpaces)) {
            throw new InvalidParameterValueException("Username should be non empty string");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                for (final Long hostId : hostIds) {
                    logger.debug("Changing password for {}", () -> hostDao.findById(hostId));
                    // update password for this host
                    final DetailVO nv = hostDetailsDao.findDetail(hostId, ApiConstants.USERNAME);
                    if (nv == null) {
                        final DetailVO nvu = new DetailVO(hostId, ApiConstants.USERNAME, userNameWithoutSpaces);
                        hostDetailsDao.persist(nvu);
                        final DetailVO nvp = new DetailVO(hostId, ApiConstants.PASSWORD, DBEncryptionUtil.encrypt(command.getPassword()));
                        hostDetailsDao.persist(nvp);
                    } else if (nv.getValue().equals(userNameWithoutSpaces)) {
                        final DetailVO nvp = hostDetailsDao.findDetail(hostId, ApiConstants.PASSWORD);
                        nvp.setValue(DBEncryptionUtil.encrypt(command.getPassword()));
                        hostDetailsDao.persist(nvp);
                    } else {
                        // if one host in the cluster has diff username then
                        // rollback to maintain consistency
                        throw new InvalidParameterValueException("The username is not same for all hosts, please modify passwords for individual hosts.");
                    }
                }
            }
        });
        return true;
    }
}
