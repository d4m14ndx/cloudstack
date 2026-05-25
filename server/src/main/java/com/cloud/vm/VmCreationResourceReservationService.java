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

import org.apache.cloudstack.resourcelimit.Reserver;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

public interface VmCreationResourceReservationService {

    @FunctionalInterface
    interface VmCreationOperation {
        UserVm create() throws Exception;
    }

    UserVm reserveComputeResources(Account owner, ServiceOfferingVO offering, VMTemplateVO template,
            VmCreationOperation operation) throws ResourceAllocationException;

    List<String> getResourceLimitStorageTags(long diskOfferingId);

    void reserveStorageResourcesForVm(List<Reserver> checkedReservations, Account owner, Long diskOfferingId,
            Long diskSize, List<VmDiskInfo> dataDiskInfoList, Long rootDiskOfferingId, ServiceOfferingVO offering,
            Long rootDiskSize) throws ResourceAllocationException;

    void checkVolumesLimits(Account account, List<VolumeVO> volumes, List<Reserver> reservations)
            throws ResourceAllocationException;
}
