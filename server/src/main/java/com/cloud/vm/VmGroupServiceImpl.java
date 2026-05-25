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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.user.vmgroup.CreateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.DeleteVMGroupCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.InstanceGroupDao;
import com.cloud.vm.dao.InstanceGroupVMMapDao;
import com.cloud.vm.dao.UserVmDao;

/**
 * VM group management — extracted from {@link UserVmManagerImpl} as part of
 * the Phase 4 god-class decomposition.
 *
 * @see VmGroupService
 */
@Component
public class VmGroupServiceImpl implements VmGroupService {
    private static final Logger LOG = LogManager.getLogger(VmGroupServiceImpl.class);

    @Inject
    private AccountManager accountManager;
    @Inject
    private AccountDao accountDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private InstanceGroupDao vmGroupDao;
    @Inject
    private InstanceGroupVMMapDao groupVmMapDao;
    @Inject
    private AnnotationDao annotationDao;

    @Override
    @DB
    public InstanceGroupVO createVmGroup(CreateVMGroupCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        String accountName = cmd.getAccountName();
        String groupName = cmd.getGroupName();
        Long projectId = cmd.getProjectId();

        Account owner = accountManager.finalizeOwner(caller, accountName, domainId, projectId);
        long accountId = owner.getId();

        if (vmGroupDao.isNameInUse(accountId, groupName)) {
            throw new InvalidParameterValueException(String.format(
                    "Unable to create Instance group, a group with name %s already exists for Account %s",
                    groupName, owner));
        }

        return createVmGroup(groupName, accountId);
    }

    @Override
    @DB
    public InstanceGroupVO createVmGroup(String groupName, long accountId) {
        Account account = null;
        try {
            // Lock the account row so duplicate group names can't be created concurrently.
            account = accountDao.acquireInLockTable(accountId);
            if (account == null) {
                LOG.warn("Failed to acquire lock on Account");
                return null;
            }
            InstanceGroupVO group = vmGroupDao.findByAccountAndName(accountId, groupName);
            if (group == null) {
                group = new InstanceGroupVO(groupName, accountId);
                group = vmGroupDao.persist(group);
            }
            return group;
        } finally {
            if (account != null) {
                accountDao.releaseFromLockTable(accountId);
            }
        }
    }

    @Override
    public boolean deleteVmGroup(DeleteVMGroupCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long groupId = cmd.getId();

        InstanceGroupVO group = vmGroupDao.findById(groupId);
        if (group == null || group.getRemoved() != null) {
            throw new InvalidParameterValueException("unable to find a vm group with id " + groupId);
        }

        accountManager.checkAccess(caller, null, true, group);
        return deleteVmGroup(groupId);
    }

    @Override
    public boolean deleteVmGroup(long groupId) {
        InstanceGroupVO group = vmGroupDao.findById(groupId);
        annotationDao.removeByEntityType(AnnotationService.EntityType.INSTANCE_GROUP.name(), group.getUuid());

        List<InstanceGroupVMMapVO> groupVmMaps = groupVmMapDao.listByGroupId(groupId);
        for (InstanceGroupVMMapVO groupMap : groupVmMaps) {
            SearchCriteria<InstanceGroupVMMapVO> sc = groupVmMapDao.createSearchCriteria();
            sc.addAnd("instanceId", SearchCriteria.Op.EQ, groupMap.getInstanceId());
            groupVmMapDao.expunge(sc);
        }

        return vmGroupDao.remove(groupId);
    }

    @Override
    @DB
    public boolean addInstanceToGroup(final long userVmId, String groupName) {
        UserVmVO vm = vmDao.findById(userVmId);

        InstanceGroupVO group = vmGroupDao.findByAccountAndName(vm.getAccountId(), groupName);
        if (group == null) {
            group = createVmGroup(groupName, vm.getAccountId());
        }
        if (group == null) {
            return false;
        }

        UserVm userVm = vmDao.acquireInLockTable(userVmId);
        if (userVm == null) {
            LOG.warn("Failed to acquire lock on user vm {} with id {}", vm, userVmId);
        }
        try {
            final InstanceGroupVO groupFinal = group;
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    // Lock the group so it can't be deleted while we're assigning a VM to it.
                    InstanceGroupVO ngrpLock = vmGroupDao.lockRow(groupFinal.getId(), false);
                    if (ngrpLock == null) {
                        LOG.warn("Failed to acquire lock on Instance group {}", groupFinal);
                        throw new CloudRuntimeException(String.format(
                                "Failed to acquire lock on Instance group %s", groupFinal));
                    }

                    // Currently a VM can only belong to a single group: replace any existing membership.
                    if (groupVmMapDao.listByInstanceId(userVmId) != null) {
                        List<InstanceGroupVMMapVO> existing = groupVmMapDao.listByInstanceId(userVmId);
                        for (InstanceGroupVMMapVO groupMap : existing) {
                            SearchCriteria<InstanceGroupVMMapVO> sc = groupVmMapDao.createSearchCriteria();
                            sc.addAnd("instanceId", SearchCriteria.Op.EQ, groupMap.getInstanceId());
                            groupVmMapDao.expunge(sc);
                        }
                    }
                    InstanceGroupVMMapVO mapVO = new InstanceGroupVMMapVO(groupFinal.getId(), userVmId);
                    groupVmMapDao.persist(mapVO);
                }
            });
            return true;
        } finally {
            if (userVm != null) {
                vmDao.releaseFromLockTable(userVmId);
            }
        }
    }

    @Override
    public InstanceGroupVO getGroupForVm(long vmId) {
        // TODO: a VM can be assigned to multiple groups in the future; for now return the first.
        try {
            List<InstanceGroupVMMapVO> groupsToVmMap = groupVmMapDao.listByInstanceId(vmId);
            if (groupsToVmMap != null && !groupsToVmMap.isEmpty()) {
                return vmGroupDao.findById(groupsToVmMap.get(0).getGroupId());
            }
            return null;
        } catch (Exception e) {
            LOG.warn("Error trying to get group for a vm: ", e);
            return null;
        }
    }

    @Override
    public void removeInstanceFromInstanceGroup(long vmId) {
        try {
            List<InstanceGroupVMMapVO> groupVmMaps = groupVmMapDao.listByInstanceId(vmId);
            for (InstanceGroupVMMapVO groupMap : groupVmMaps) {
                SearchCriteria<InstanceGroupVMMapVO> sc = groupVmMapDao.createSearchCriteria();
                sc.addAnd("instanceId", SearchCriteria.Op.EQ, groupMap.getInstanceId());
                groupVmMapDao.expunge(sc);
            }
        } catch (Exception e) {
            LOG.warn("Error trying to remove vm from group: ", e);
        }
    }
}
