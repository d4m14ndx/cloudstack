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

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.backup.BackupOffering;

import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;

public interface AccountAccessService {
    boolean isAdmin(Long accountId);

    boolean isRootAdmin(Long accountId);

    boolean isDomainAdmin(Long accountId);

    boolean isNormalUser(long accountId);

    boolean isResourceDomainAdmin(Long accountId);

    boolean isInternalAccount(long accountId);

    void checkAccess(Account caller, Domain domain) throws PermissionDeniedException;

    void checkAccess(Account caller, AccessType accessType, boolean sameOwner, ControlledEntity... entities);

    void checkAccess(Account caller, AccessType accessType, boolean sameOwner, String apiName, ControlledEntity... entities);

    void validateAccountHasAccessToResource(Account account, AccessType accessType, Object resource);

    Long checkAccessAndSpecifyAuthority(Account caller, Long zoneId);

    void checkAccess(Account account, ServiceOffering serviceOffering, DataCenter zone) throws PermissionDeniedException;

    void checkAccess(Account account, DiskOffering diskOffering, DataCenter zone) throws PermissionDeniedException;

    void checkAccess(Account account, NetworkOffering networkOffering, DataCenter zone) throws PermissionDeniedException;

    void checkAccess(Account account, VpcOffering vpcOffering, DataCenter zone) throws PermissionDeniedException;

    void checkAccess(Account account, BackupOffering backupOffering) throws PermissionDeniedException;

    void checkAccess(User user, ControlledEntity entity) throws PermissionDeniedException;
}
