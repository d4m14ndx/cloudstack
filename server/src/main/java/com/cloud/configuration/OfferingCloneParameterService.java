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
package com.cloud.configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneServiceOfferingCmd;

import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.vm.VirtualMachine;

public interface OfferingCloneParameterService {

    ServiceOfferingVO getAndValidateSourceOffering(Long sourceOfferingId);

    DiskOfferingVO getSourceDiskOffering(ServiceOfferingVO sourceOffering);

    <T> T getOrDefault(T cmdValue, T defaultValue);

    Boolean resolveBooleanParam(Map<String, String> requestParams, String paramKey, Supplier<Boolean> cmdValueSupplier, Boolean defaultValue);

    String resolveProvisioningType(CloneServiceOfferingCmd cmd, DiskOfferingVO sourceDiskOffering);

    String resolveStorageType(CloneServiceOfferingCmd cmd, DiskOfferingVO sourceDiskOffering);

    List<Long> resolveDomainIds(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering);

    List<Long> resolveZoneIds(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering);

    OfferingCloneParameterServiceImpl.ClonedDiskOfferingParams resolveDiskOfferingParams(CloneServiceOfferingCmd cmd, DiskOfferingVO sourceDiskOffering);

    OfferingCloneParameterServiceImpl.CustomOfferingParams resolveCustomOfferingParams(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering, Boolean isCustomized);

    Boolean resolvePurgeResources(CloneServiceOfferingCmd cmd, Map<String, String> requestParams, ServiceOfferingVO sourceOffering);

    OfferingCloneParameterServiceImpl.LeaseParams resolveLeaseParams(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering);

    Map<String, String> mergeOfferingDetails(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering, OfferingCloneParameterServiceImpl.CustomOfferingParams customParams);

    VirtualMachine.Type resolveVmType(ServiceOfferingVO sourceOffering);

    DiskOfferingVO getAndValidateSourceDiskOffering(Long sourceOfferingId);

    List<Long> resolveDomainIdsForDiskOffering(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    List<Long> resolveZoneIdsForDiskOffering(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    boolean resolveLocalStorageRequired(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    String resolveCacheMode(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    Long resolveStoragePolicyForDiskOffering(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    OfferingCloneParameterServiceImpl.ClonedDiskIopsParams resolveDiskIopsParams(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    OfferingCloneParameterServiceImpl.ClonedDiskRateParams resolveDiskRateParams(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);

    Map<String, String> mergeDiskOfferingDetails(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering);
}
