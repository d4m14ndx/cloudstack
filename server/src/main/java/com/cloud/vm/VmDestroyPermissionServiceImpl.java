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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.admin.vm.ExpungeVMCmd;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.stereotype.Component;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.ComponentContext;

/**
 * Destroy/expunge/force-stop permission checks — extracted from
 * {@link UserVmManagerImpl}.
 *
 * @see VmDestroyPermissionService
 */
@Component
public class VmDestroyPermissionServiceImpl implements VmDestroyPermissionService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountManager accountManager;

    @Override
    public void checkExpungeVmPermission(Account callingAccount, String apiKey) {
        logger.debug("Checking if [{}] has permission for expunging VMs.", callingAccount);
        if (!accountManager.isAdmin(callingAccount.getId()) && !isUserExpungeRecoverVmAllowed(callingAccount.getId())) {
            logger.error("Parameter [{}] can only be passed by Admin accounts or when the allow.user.expunge.recover.vm key is true.", ApiConstants.EXPUNGE);
            throw new PermissionDeniedException("Account does not have permission for expunging.");
        }
        try {
            accountManager.checkApiAccess(callingAccount, BaseCmd.getCommandNameByClass(ExpungeVMCmd.class), apiKey);
        } catch (PermissionDeniedException ex) {
            logger.error("Role [{}] of [{}] does not have permission for expunging VMs.", callingAccount.getRoleId(), callingAccount);
            throw new PermissionDeniedException("Account does not have permission for expunging.");
        }
    }

    @Override
    public boolean isUserExpungeRecoverVmAllowed(Long accountId) {
        return UserVmManager.AllowUserExpungeRecoverVm.valueIn(accountId);
    }

    @Override
    public void checkPluginsIfVmCanBeDestroyed(UserVm vm) {
        try {
            KubernetesServiceHelper kubernetesServiceHelper =
                    ComponentContext.getDelegateComponentOfType(KubernetesServiceHelper.class);
            kubernetesServiceHelper.checkVmCanBeDestroyed(vm);
        } catch (NoSuchBeanDefinitionException ignored) {
            logger.debug("No KubernetesClusterHelper bean found");
        }
    }

    @Override
    public void checkForceStopVmPermission(Account callingAccount) {
        if (!isUserForceStopVmAllowed(callingAccount.getId())) {
            logger.error("Parameter [{}] can only be passed by Admin accounts or when the allow.user.force.stop.vm config is true for the account.", ApiConstants.FORCED);
            throw new PermissionDeniedException("Account does not have the permission to force stop the vm.");
        }
    }

    /**
     * Encapsulates the lookup of {@code allow.user.force.stop.vm} so it
     * can be stubbed in unit tests. Package-private + non-final so spies
     * can override it without exposing a separate interface method.
     */
    protected boolean isUserForceStopVmAllowed(Long accountId) {
        return UserVmManager.AllowUserForceStopVm.valueIn(accountId);
    }
}
