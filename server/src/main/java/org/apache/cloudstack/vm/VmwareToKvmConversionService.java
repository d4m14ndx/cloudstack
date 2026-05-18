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
package org.apache.cloudstack.vm;

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.agent.api.CheckConvertInstanceAnswer;
import com.cloud.agent.api.ConvertInstanceCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.RemoteInstanceTO;
import com.cloud.host.HostVO;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;

/**
 * VMware-to-KVM cross-hypervisor migration helpers extracted from
 * {@link UnmanagedVMsManagerImpl}. The service collects the
 * conversion-storage validation, the source/cloned-template lifecycle
 * helpers, the convert/import agent-command plumbing, and the
 * conversion-pool selection logic.
 *
 * <p>The orchestration entry point ({@code importUnmanagedInstanceFromVmwareToKvm})
 * stays in {@link UnmanagedVMsManagerImpl} because it calls back into
 * the manager's private {@code importVirtualMachineInternal}; that
 * orchestrator delegates each individual step to this service.
 *
 * <p>Part of the Phase 4 Spring-component decomposition; the manager
 * keeps one-line delegating wrappers so existing spy-based tests and
 * external orchestration paths continue to work unchanged.
 */
public interface VmwareToKvmConversionService {

    /**
     * Add the minimum CPU/memory resources that the source VMware VM
     * must satisfy, sourced from the offering or its
     * {@code MIN_CPU_NUMBER}/{@code MIN_MEMORY} details.
     */
    void addServiceOfferingDetailsToParams(Map<String, String> params, ServiceOfferingVO serviceOffering);

    /**
     * Copy the source-VMware CPU / memory / power-state fields onto the
     * just-converted instance and reconcile disk / NIC identifiers and
     * MAC addresses from the source where the convert XML did not
     * surface them.
     */
    void sanitizeConvertedInstance(UnmanagedInstanceTO convertedInstance, UnmanagedInstanceTO sourceVMwareInstance);

    /**
     * Build the param map required to remove a cloned VMware instance
     * (used in the {@code finally} of the conversion path).
     */
    Map<String, String> createParamsForRemoveClonedInstance(String vcenter, String datacenterName,
                                                            String username, String password, String sourceVM);

    /**
     * Build the param map used to drive both source-VM discovery and
     * out-of-band OVF export from vCenter.
     */
    Map<String, String> createParamsForTemplateFromVmwareVmMigration(String vcenterHost, String datacenterName,
                                                                    String username, String password,
                                                                    String clusterName, String sourceHostName,
                                                                    String sourceVMName);

    /**
     * Best-effort remove of a previously-cloned VMware instance via the
     * VMware hypervisor guru; logs and swallows failures so the caller
     * cleanup chain never aborts.
     */
    void removeClonedInstance(String vcenter, String datacenterName, String username, String password,
                              String sourceHostName, String clonedInstanceName, String sourceVM);

    /**
     * Best-effort remove of the temporary OVF template left on the
     * conversion data store; logs and swallows failures.
     */
    void removeTemplate(DataStoreTO convertLocation, String ovfTemplateOnConvertLocation);

    /**
     * Reject mismatched {@code forceConvertToPool} / pool-id
     * combinations, missing pools, and primary pool types not in the
     * direct-conversion allow-list.
     */
    void checkConversionStoragePool(Long convertStoragePoolId, boolean forceConvertToPool);

    /**
     * When VDDK is used the selected conversion pool becomes the final
     * primary storage; verify every disk offering (root + data) is
     * compatible with that pool.
     */
    void validateSelectedConversionStoragePoolForVddk(boolean useVddk, Long convertStoragePoolId,
                                                      ServiceOfferingVO serviceOffering,
                                                      Map<String, Long> dataDiskOfferingMap);

    /**
     * Copy VDDK lib / transport / thumbprint host details onto an
     * outbound {@link ConvertInstanceCommand}.
     */
    void applyVddkOverridesFromDetails(ConvertInstanceCommand cmd, Map<String, String> details);

    /**
     * Pick a storage pool whose tags satisfy the disk-offering tag
     * predicate; falls back to the first pool when {@code tags} is
     * blank.
     */
    StoragePoolVO getStoragePoolWithTags(List<StoragePoolVO> pools, String tags);

    /**
     * Pair each source disk with a destination storage pool in the
     * cluster, honouring root vs. data offering tag affinity.
     */
    List<String> selectInstanceConversionStoragePools(List<StoragePoolVO> pools, List<UnmanagedInstanceTO.Disk> disks,
                                                      ServiceOfferingVO serviceOffering,
                                                      Map<String, Long> dataDiskOfferingMap);

    /**
     * Filter a host list down to those advertising
     * {@code Host.HOST_VDDK_SUPPORT=true}.
     */
    List<HostVO> filterHostsWithVddkSupport(List<HostVO> hosts);

    /**
     * Ask the convert host whether it can perform a VDDK / Windows /
     * standard conversion ahead of the actual convert call.
     */
    CheckConvertInstanceAnswer checkConversionSupportOnHost(HostVO convertHost, String sourceVM,
                                                            boolean checkWindowsGuestConversionSupport,
                                                            boolean useVddk, Map<String, String> details);

    /**
     * Run a previously-prepared {@link ConvertInstanceCommand} on the
     * convert host, then the matching
     * {@link com.cloud.agent.api.ImportConvertedInstanceCommand} on the
     * import host. Returns the imported converted instance.
     */
    UnmanagedInstanceTO convertAndImportToKVM(ConvertInstanceCommand convertInstanceCommand, HostVO convertHost,
                                              HostVO importHost, String sourceVM, RemoteInstanceTO remoteInstanceTO,
                                              List<String> destinationStoragePools, DataStoreTO temporaryConvertLocation,
                                              boolean forceConvertToPool);

    /**
     * Resolve the final list of cluster-scoped destination storage
     * pools (NFS by default, or just the forced pool when
     * {@code forceConvertToPool=true}) and verify offering compatibility.
     */
    List<StoragePoolVO> findInstanceConversionDestinationStoragePoolsInCluster(Cluster destinationCluster,
                                                                              ServiceOfferingVO serviceOffering,
                                                                              Map<String, Long> dataDiskOfferingMap,
                                                                              DataStoreTO temporaryConvertLocation,
                                                                              boolean forceConvertToPool);

    /**
     * Decide where to stage the in-flight conversion: either an NFS
     * secondary store in the destination zone (when no explicit pool
     * is given) or the supplied primary pool after scope / type /
     * locality checks.
     */
    DataStoreTO selectInstanceConversionTemporaryLocation(Cluster destinationCluster, HostVO convertHost,
                                                          HostVO importHost, Long convertStoragePoolId,
                                                          boolean forceConvertToPool);
}
