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
package com.cloud.storage;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;

/**
 * Host- and device-topology helpers backing the volume attach / detach
 * orchestration in {@link VolumeApiServiceImpl}. These are the small
 * "where is this volume actually going to be plugged in" decisions that
 * sit between the high-level API entry points
 * ({@code attachVolumeToVM}, {@code detachVolumeFromVM},
 * {@code sendAttachVolumeCommand}) and the storage / hypervisor layer:
 * <ul>
 *   <li>picking a host to issue the attach/detach command against, and
 *       deciding whether we even need to send one
 *       ({@link #getHostForVmVolumeAttachDetach},
 *       {@link #isSendCommandForVmVolumeAttachDetach});</li>
 *   <li>guarding managed-storage scalability and producing the
 *       hypervisor-specific clustered-filesystem label used in error
 *       messages ({@link #verifyManagedStorage},
 *       {@link #getNameOfClusteredFileSystem});</li>
 *   <li>resolving KVM-specific VM detail keys for the disk:
 *       {@code iothreads} support ({@link #isIothreadsSupported}) and
 *       the {@code io.policy} driver
 *       ({@link #getIoPolicy});</li>
 *   <li>pushing fresh VM info into the primary store driver when it
 *       advertises that it needs it
 *       ({@link #provideVMInfo});</li>
 *   <li>sizing the per-VM data-disk envelope and allocating the next
 *       free SCSI/IDE device id
 *       ({@link #getMaxDataVolumesSupported},
 *       {@link #getMinimumHypervisorVersionInDatacenter},
 *       {@link #getDeviceId}).</li>
 * </ul>
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase
 * 4 Spring-component decomposition (parallel slice, 6th). The host
 * class keeps thin delegating wrappers so the orchestration in
 * {@code sendAttachVolumeCommand} and {@code orchestrateDetachVolume*}
 * can still be exercised by the existing spy-verified manager-level
 * tests, while the underlying logic is now covered by focused unit
 * tests against this component.</p>
 */
public interface VolumeHostTopologyService {

    /**
     * Guard the attach against an over-subscribed managed-storage
     * pool: if the volume is on a managed pool and we have a target
     * host, ask {@link StorageUtil#managedStoragePoolCanScale} whether
     * the pool can still grow on the host's cluster. Throws
     * {@link com.cloud.utils.exception.CloudRuntimeException} when it
     * can't, with a hypervisor-appropriate "Insufficient number of
     * available {SRs|datastores|clustered file systems}" message. No-op
     * when either id is {@code null}, the pool is unmanaged, or the
     * host can't be found.
     */
    void verifyManagedStorage(Long storagePoolId, Long hostId);

    /**
     * The hypervisor-specific noun for the clustered filesystem that
     * managed storage maps to on the host — {@code "SRs"} for
     * XenServer, {@code "datastores"} for VMware, and the generic
     * {@code "clustered file systems"} for everything else. Used in
     * error messages from {@link #verifyManagedStorage}.
     */
    String getNameOfClusteredFileSystem(HostVO hostVO);

    /**
     * Pick a host to issue the attach/detach command against. If the
     * VM already has a current host, use it. Otherwise, when the VM is
     * stopped and the volume is on non-host-scoped storage, fall back
     * to any hypervisor host in the VM's cluster — the storage layer
     * may still need a live host context to flip the LUN. Returns
     * {@code null} when neither path can name one.
     */
    HostVO getHostForVmVolumeAttachDetach(VirtualMachine vm, StoragePoolVO volumeStoragePool);

    /**
     * True when the attach/detach must be sent as a live command to
     * the hypervisor host even though the VM may not be running —
     * VMware always (datastore mapping) and XenServer when the volume
     * is on managed storage (SR plug). False when either argument is
     * {@code null}, or for any other hypervisor/pool combination.
     */
    boolean isSendCommandForVmVolumeAttachDetach(HostVO host, StoragePoolVO volumeStoragePool);

    /**
     * True when this is a KVM VM that has the {@code iothreads} detail
     * set — qemu can then bind the disk to an iothread.
     */
    boolean isIothreadsSupported(UserVmVO vm);

    /**
     * Resolve the qemu {@code io} policy for a KVM disk attached to
     * {@code vm} on the given pool. Honors the
     * {@link com.cloud.vm.VmDetailConstants#IO_POLICY} VM detail,
     * including the {@code storage_specific} indirection that defers
     * to {@code StorageManager.STORAGE_POOL_IO_POLICY} on the pool.
     * Returns {@code null} for non-KVM VMs or when no policy is set.
     */
    String getIoPolicy(UserVmVO vm, long poolId);

    /**
     * When the destination is a primary store whose driver opts into
     * {@code isVmInfoNeeded()}, push the {@code (vmId, volumeId)} pair
     * through {@code PrimaryDataStoreDriver#provideVmInfo} so the
     * driver can tag the LUN. No-op for stores that don't need it.
     */
    void provideVMInfo(DataStore dataStore, long vmId, Long volumeId);

    /**
     * The maximum number of data-disk devices this VM's hypervisor
     * can address — looked up from
     * {@code hypervisor_capabilities.max_data_volumes_limit} keyed on
     * the running host's product_version, with a "default" entry as
     * fallback for the configured default-hypervisor set, and a hard
     * floor of 6.
     */
    int getMaxDataVolumesSupported(UserVmVO vm);

    /**
     * Pick the lowest hypervisor version actually deployed in the
     * datacenter for the given hypervisor type, used as the lookup
     * key into {@code hypervisor_capabilities}. Returns
     * {@code "default"} for {@link HypervisorType#Simulator}, for
     * empty version lists, or when the lowest version string is
     * blank.
     */
    String getMinimumHypervisorVersionInDatacenter(long datacenterId, HypervisorType hypervisorType);

    /**
     * Allocate a usable device id for a new data volume on
     * {@code vm}. If the caller specified a {@code deviceId}, validate
     * that it is in {@code [0, maxDataVolumes+1]}, isn't the reserved
     * cdrom slot (3), and isn't already in use. Otherwise pick the
     * lowest free non-3 device id in range. Throws a
     * {@link RuntimeException} when the device id is out of range,
     * already in use, or no free slot remains.
     */
    Long getDeviceId(UserVmVO vm, Long deviceId);
}
