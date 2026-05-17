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

import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.resourcelimit.Reserver;

import com.cloud.dc.DataCenter;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.storage.StoragePool;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

/**
 * Pre-flight validation helpers for the disk / storage-pool side of
 * the unmanaged-VM import flow — partitions discovered hypervisor
 * disks into root + data, locates the CloudStack storage pool that
 * backs each datastore, and verifies the chosen disk offering is
 * compatible with the disk size, owner permissions, storage pool
 * tags, and the account's volume resource limits.
 *
 * <p>Extracted from {@link UnmanagedVMsManagerImpl} as part of the
 * Phase 4 Spring-component decomposition. {@code UnmanagedVMsManagerImpl}
 * keeps delegating wrappers around each of these calls so existing
 * orchestration paths and any test spies on the manager continue to
 * work unchanged.
 */
public interface UnmanagedInstanceDiskValidator {

    /**
     * Locate the CloudStack primary storage pool that backs the
     * datastore reported by the source hypervisor for the given
     * {@code disk}. The pool must live in the supplied zone, either
     * be cluster-wide or zone-wide for the import cluster, and
     * satisfy the disk offering tags. Throws
     * {@link ServerApiException} if no compatible pool is found.
     */
    StoragePool getStoragePool(UnmanagedInstanceTO.Disk disk, DataCenter zone, Cluster cluster, DiskOffering diskOffering);

    /**
     * Return {@code true} only when both {@code pool} and
     * {@code diskOffering} are non-null and the volume service
     * reports the pool can host volumes of that offering.
     */
    boolean storagePoolSupportsDiskOffering(StoragePool pool, DiskOffering diskOffering);

    /**
     * Partition the unmanaged instance's disks into a single root
     * disk and the remaining data disks, using the caller-supplied
     * data-disk-offering map: the disk whose id is <em>not</em>
     * present in {@code dataDiskOfferingMap} is treated as the root.
     * Throws when the map size is inconsistent with the disk count.
     */
    Pair<UnmanagedInstanceTO.Disk, List<UnmanagedInstanceTO.Disk>> getRootAndDataDisks(
            List<UnmanagedInstanceTO.Disk> disks, Map<String, Long> dataDiskOfferingMap);

    /**
     * Validate a single unmanaged disk against the supplied disk /
     * service offering — checks that an offering exists, the owner
     * has access to it, the disk has a non-zero capacity, the
     * offering's fixed size accommodates the source disk, the
     * located storage pool supports the offering (unless migration
     * is allowed), and that booking the disk respects the account's
     * volume resource limits.
     */
    void checkUnmanagedDiskAndOfferingForImport(String instanceName, UnmanagedInstanceTO.Disk disk,
                                                DiskOffering diskOffering, ServiceOffering serviceOffering,
                                                Account owner, DataCenter zone, Cluster cluster,
                                                boolean migrateAllowed, List<Reserver> reservations)
            throws ServerApiException, PermissionDeniedException, ResourceAllocationException;

    /**
     * Validate every data disk in a multi-disk import: every disk
     * must have an entry in {@code diskOfferingMap}, every disk
     * controller must match, and each disk individually must pass
     * the single-disk validator above.
     */
    void checkUnmanagedDiskAndOfferingForImport(String instanceName, List<UnmanagedInstanceTO.Disk> disks,
                                                Map<String, Long> diskOfferingMap, Account owner,
                                                DataCenter zone, Cluster cluster, boolean migrateAllowed,
                                                List<Reserver> reservations)
            throws ServerApiException, PermissionDeniedException, ResourceAllocationException;
}
