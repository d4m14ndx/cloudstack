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

import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.vm.lease.VMLeaseManager;

import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.vm.VirtualMachine;

/**
 * Service-offering CRUD operations — create, update, delete and lookup of a
 * {@code SERVICE_OFFERING} from an admin or domain-admin command. Extracted
 * from {@link ConfigurationManagerImpl} as the fourth parallel slice of the
 * Phase&nbsp;4 Spring-component decomposition.
 *
 * <p>{@code ConfigurationManagerImpl} retains thin delegating wrappers so the
 * {@code ConfigurationService} / {@link ConfigurationManager} interface
 * contracts keep working unchanged. The protected
 * {@code createServiceOffering(userId, ...)} overload remains on the manager
 * because {@code cloneServiceOffering} calls it directly, but that wrapper
 * delegates to this service-owned write path.
 *
 * <p>The helpers {@code updateServiceOfferingHostTagsIfNotNull},
 * {@code serviceOfferingExternalDetailsNeedUpdate},
 * {@code validateExtraConfigInServiceOfferingDetail},
 * {@code validateAndGetLeaseExpiryAction} and the rate-validation /
 * cache-mode / tag helpers also remain on the manager — they have callers
 * outside this slice (notably {@code cloneServiceOffering} and the disk
 * offering / network offering flows) — and are duplicated inside
 * {@link ServiceOfferingServiceImpl} so the slice carries no back-reference
 * into the manager.
 */
public interface ServiceOfferingService {

    /**
     * Create a new service offering from a {@link CreateServiceOfferingCmd}.
     * Validates name / display text, custom vs fixed CPU / memory parameters,
     * domain / zone references, cache-mode enum, storage type / HA
     * combination, system-VM type, network-rate restrictions, deployment
     * planner, vSphere storage policy, disk offering link, lease properties,
     * and vGPU profile / GPU count; then persists the offering (creating a
     * companion disk offering when none is referenced) and writes any
     * domain-id, zone-id and detail rows.
     */
    ServiceOffering createServiceOffering(CreateServiceOfferingCmd cmd);

    ServiceOfferingVO createServiceOffering(long userId, boolean isSystem, VirtualMachine.Type vmType,
            String name, Integer cpu, Integer ramSize, Integer speed, String displayText, String provisioningType,
            boolean localStorageRequired, boolean offerHA, boolean limitResourceUse, boolean volatileVm, String tags,
            List<Long> domainIds, List<Long> zoneIds, String hostTag, Integer networkRate, String deploymentPlanner,
            Map<String, String> details, Long rootDiskSizeInGiB, Boolean isCustomizedIops, Long minIops, Long maxIops,
            Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength, Long bytesWriteRate,
            Long bytesWriteRateMax, Long bytesWriteRateMaxLength, Long iopsReadRate, Long iopsReadRateMax,
            Long iopsReadRateMaxLength, Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength,
            Integer hypervisorSnapshotReserve, String cacheMode, Long storagePolicyID, boolean dynamicScalingEnabled,
            Long diskOfferingId, boolean diskOfferingStrictness, boolean isCustomized, boolean encryptRoot,
            Long vgpuProfileId, Integer gpuCount, Boolean gpuDisplay, boolean purgeResources, Integer leaseDuration,
            VMLeaseManager.ExpiryAction leaseExpiryAction);

    /**
     * Update an existing service offering. Re-validates domain / zone
     * references, applies any name / displayText / sortKey / storage-tag /
     * host-tag / state change, persists domain-id / zone-id detail rows when
     * they differ from the current binding, applies external-detail and
     * purge-resources updates, and propagates state changes to the linked
     * compute-only disk offering. Domain-admin callers are restricted from
     * changing the zone binding and from broadening the offering past their
     * child-domain tree.
     */
    ServiceOffering updateServiceOffering(UpdateServiceOfferingCmd cmd);

    /**
     * Soft-delete a service offering by setting its state to {@code Inactive}.
     * Verifies the caller is root admin (or domain-admin restricted to a
     * child-domain offering), removes any annotations attached to the
     * offering, and also deactivates the linked compute-only disk offering.
     */
    boolean deleteServiceOffering(DeleteServiceOfferingCmd cmd);

    /**
     * Return the list of domain ids the given service offering is restricted
     * to, or an empty list when the offering is public.
     */
    List<Long> getServiceOfferingDomains(Long serviceOfferingId);

    /**
     * Return the list of zone ids the given service offering is restricted
     * to, or an empty list when the offering is not scoped to a zone.
     */
    List<Long> getServiceOfferingZones(Long serviceOfferingId);
}
