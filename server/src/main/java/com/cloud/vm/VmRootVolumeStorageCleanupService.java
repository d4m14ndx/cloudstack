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

import com.cloud.storage.VolumeVO;

/**
 * Hypervisor-aware managed-storage cleanup invoked from the restore-VM
 * flow after the old root volume has been swapped out for a freshly
 * allocated one.
 *
 * <p>For VMs whose ROOT volume lives on managed storage, simply detaching
 * the volume in the DB is not enough — the underlying storage backend
 * (XenServer SR, VMware datastore, KVM block device) still needs to be
 * told to release or remove the volume's iSCSI/SCSI mapping. The
 * specific command varies by hypervisor:
 *
 * <ul>
 *   <li><b>XenServer</b>: sends a {@code DettachCommand} marked
 *       {@code managed} so the SR backing the root volume is removed.</li>
 *   <li><b>VMware</b>: sends a {@code DeleteCommand} with the managed
 *       flag set on the datastore details, then follows up with a
 *       {@code ModifyTargetsCommand} broadcast cluster-wide to drop the
 *       dynamic iSCSI target.</li>
 *   <li><b>KVM</b>: no agent command needed — the host detaches the
 *       device as part of the VM lifecycle.</li>
 *   <li>Any other hypervisor type with managed storage is rejected with
 *       a {@code CloudRuntimeException}.</li>
 * </ul>
 *
 * <p>After the per-hypervisor command, the helper revokes the host's
 * access to the volume through the volume manager and, for VMware,
 * cleans the dynamic-target entry across every host in the cluster.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 18). The god class keeps a
 * one-line {@code handleManagedStorage} wrapper so existing call sites
 * are unchanged.
 */
public interface VmRootVolumeStorageCleanupService {

    /**
     * Run the post-restore cleanup for {@code root} on its current
     * managed storage pool. No-op when the volume is still
     * {@link com.cloud.storage.Volume.State#Allocated} (never started
     * → nothing was attached on the backend), when the pool is not
     * managed, when the VM has neither a current nor last host, or when
     * the host record cannot be found.
     *
     * @param vm    the VM whose root volume is being replaced
     * @param root  the outgoing root volume (about to be destroyed)
     */
    void cleanupRootVolumeOnManagedStorage(UserVmVO vm, VolumeVO root);
}
